package com.bizplay.builder.intake;

import com.bizplay.builder.config.FlowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Flow 업무번호를 찾고 게시물 상세 원문을 읽는다. */
@Component
public class HttpFlowPostGateway implements FlowPostGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpFlowPostGateway.class);
    private final FlowProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public HttpFlowPostGateway(FlowProperties properties, ObjectMapper json) {
        this(properties, json, HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build());
    }

    HttpFlowPostGateway(FlowProperties properties, ObjectMapper json, HttpClient http) {
        this.properties = properties;
        this.json = json;
        this.http = http;
    }

    @Override
    public FlowPost get(String postId) {
        if (postId == null || !postId.matches("[0-9]{1,15}")) {
            throw new IllegalArgumentException("Flow 게시물 ID는 15자리 이하 숫자로 입력해 주세요.");
        }
        return readPost(postId, requestData("/user/posts/" + postId));
    }

    @Override
    public FlowPost getByTaskNumber(String taskNumber) {
        if (taskNumber == null || !taskNumber.matches("[0-9]{4,12}")) {
            throw new IllegalArgumentException("Flow 업무번호는 4~12자리 숫자로 입력해 주세요.");
        }
        for (Project project : projects()) {
            JsonNode tasks = requestData("/user/posts/projects/" + encoded(project.id())
                    + "/tasks/filter?searchWord=" + encoded(taskNumber)).path("tasks");
            if (!tasks.isArray()) {
                continue;
            }
            for (JsonNode task : tasks) {
                if (!taskNumber.equals(taskNumber(task))) {
                    continue;
                }
                String postId = scalarText(task.path("postId"));
                if (postId == null) {
                    continue;
                }
                FlowPost post = get(postId);
                if (post.projectTitle() != null || project.title() == null) {
                    return post;
                }
                return new FlowPost(post.postId(), post.title(), post.content(), post.connectUrl(),
                        project.title(), post.attachments(), post.remarks());
            }
        }
        throw new FlowPostException(
                "Flow 업무번호를 찾지 못했습니다 — 업무번호와 접근 권한을 확인해 주세요.");
    }

    private List<Project> projects() {
        List<Project> projects = new ArrayList<>();
        Set<Long> visitedCursors = new HashSet<>();
        long cursor = 0;
        while (visitedCursors.add(cursor)) {
            JsonNode data = requestData("/user/projects?cursor=" + cursor);
            JsonNode projectsNode = data.path("projects");
            JsonNode page = projectsNode.isArray() ? data : projectsNode;
            JsonNode rows = projectsNode.isArray() ? projectsNode : page.path("projects");
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    String projectId = scalarText(row.path("projectId"));
                    if (projectId != null) {
                        projects.add(new Project(projectId, text(row, "title")));
                    }
                }
            }
            if (!page.path("hasNext").asBoolean(false)) {
                break;
            }
            long nextCursor = page.path("lastCursor").asLong(-1);
            if (nextCursor < 0 || nextCursor == cursor) {
                break;
            }
            cursor = nextCursor;
        }
        return projects;
    }

    private String taskNumber(JsonNode task) {
        JsonNode columns = task.path("columns");
        if (!columns.isArray()) {
            return null;
        }
        for (JsonNode column : columns) {
            if (!"TASK_NUM".equals(text(column, "defaultColumnType"))) {
                continue;
            }
            JsonNode values = column.path("columnData");
            if (!values.isArray()) {
                continue;
            }
            for (JsonNode value : values) {
                String taskNumber = scalarText(value.path("customColumnData"));
                if (taskNumber != null) {
                    return taskNumber.strip();
                }
            }
        }
        return null;
    }

    private JsonNode requestData(String path) {
        if (!properties.configured()) {
            throw new FlowPostException("Flow API 연결 설정이 없습니다 — 관리자에게 문의해 주세요.");
        }
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(properties.baseUrl() + path))
                .timeout(properties.timeout())
                .header("Content-Type", "application/json")
                .header("x-flow-api-key", properties.apiKey())
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("Flow 조회가 거절됐다 status={}", response.statusCode());
                throw unavailable();
            }
            JsonNode root = json.readTree(response.body());
            JsonNode envelope = root.path("response");
            if (!envelope.path("success").asBoolean(false) || !envelope.path("data").isObject()) {
                throw unavailable();
            }
            return envelope.path("data");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FlowPostException("Flow 조회가 중단됐습니다 — 다시 시도해 주세요.", interrupted);
        } catch (IOException | IllegalArgumentException failed) {
            log.warn("Flow 응답을 처리하지 못했다: {}", failed.getClass().getSimpleName());
            throw unavailable(failed);
        }
    }

    private FlowPost readPost(String requestedPostId, JsonNode data) {
        String title = text(data, "title");
        String content = content(data);
        if (title == null || content == null) {
            throw new FlowPostException("Flow 게시물에 제목이나 본문이 없습니다 — Flow에서 게시물을 확인해 주세요.");
        }
        String returnedPostId = text(data, "postId");
        return new FlowPost(returnedPostId == null ? requestedPostId : returnedPostId,
                title, content, text(data, "connectUrl"), text(data, "projectTitle"),
                attachments(data), remarks(data));
    }

    /** Flow 편집기 JSON이면 사람이 작성한 텍스트 블록만 원문으로 꺼낸다. */
    private String content(JsonNode data) {
        String plainContent = text(data, "outContent");
        if (plainContent != null) {
            return plainContent;
        }
        String rawContent = text(data, "content");
        if (rawContent == null) {
            return null;
        }
        try {
            JsonNode components = json.readTree(rawContent).path("COMPS");
            if (!components.isArray()) {
                return rawContent;
            }
            StringBuilder content = new StringBuilder();
            for (JsonNode component : components) {
                if (!"TEXT".equals(text(component, "COMP_TYPE"))) {
                    continue;
                }
                String contents = text(component.path("COMP_DETAIL"), "CONTENTS");
                if (contents == null) {
                    continue;
                }
                if (content.length() > 0) {
                    content.append("\n\n");
                }
                content.append(contents.strip());
            }
            return content.length() == 0 ? null : content.toString();
        } catch (IOException plainText) {
            return rawContent;
        }
    }

    private List<FlowPost.Attachment> attachments(JsonNode data) {
        List<FlowPost.Attachment> attachments = new ArrayList<>();
        addAttachments(attachments, data.path("attachments"));
        addAttachments(attachments, data.path("imageAttachments"));
        return attachments;
    }

    private void addAttachments(List<FlowPost.Attachment> attachments, JsonNode rows) {
        if (!rows.isArray()) {
            return;
        }
        for (JsonNode row : rows) {
            String fileName = firstText(row, "ORCP_FILE_NM", "FILE_NM");
            String url = firstText(row, "FILE_DOWN_URL", "ATCH_URL", "IMG_PATH");
            if (fileName == null && url == null) {
                continue;
            }
            attachments.add(new FlowPost.Attachment(fileName, positiveLong(row.path("FILE_SIZE")),
                    url, text(row, "THUM_IMG_PATH")));
        }
    }

    private List<FlowPost.Remark> remarks(JsonNode data) {
        JsonNode rows = data.path("remarks");
        if (!rows.isArray()) {
            return List.of();
        }
        List<FlowPost.Remark> remarks = new ArrayList<>();
        for (JsonNode row : rows) {
            if ("Y".equals(text(row, "DELETE_YN"))) {
                continue;
            }
            remarks.add(new FlowPost.Remark(text(row, "COLABO_REMARK_SRNO"),
                    text(row, "RGSR_NM"), text(row, "RGSR_ID"), text(row, "REMARK_CNTN"),
                    text(row, "RGSN_DTTM"), text(row, "PRFL_PHTG"),
                    positiveInt(row.path("REPLY_CNT")), "Y".equals(text(row, "SYSTEM_REMARK_YN"))));
        }
        return remarks;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long positiveLong(JsonNode value) {
        try {
            long number = value.isNumber() ? value.longValue() : Long.parseLong(value.asText("0"));
            return number > 0 ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int positiveInt(JsonNode value) {
        Long number = positiveLong(value);
        return number == null ? 0 : Math.toIntExact(Math.min(number, Integer.MAX_VALUE));
    }

    private static String scalarText(JsonNode value) {
        if (!value.isValueNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static FlowPostException unavailable() {
        return new FlowPostException(
                "Flow 원문을 가져오지 못했습니다 — 업무번호 또는 게시물 ID와 접근 권한을 확인해 주세요.");
    }

    private static FlowPostException unavailable(Throwable cause) {
        return new FlowPostException(
                "Flow 원문을 가져오지 못했습니다 — 업무번호 또는 게시물 ID와 접근 권한을 확인해 주세요.", cause);
    }

    private record Project(String id, String title) {
    }
}
