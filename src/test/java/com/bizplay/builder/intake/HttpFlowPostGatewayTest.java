package com.bizplay.builder.intake;

import com.bizplay.builder.config.FlowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class HttpFlowPostGatewayTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void 업무번호로_프로젝트를_검색해_첨부와_댓글이_있는_원문을_가져온다() throws Exception {
        HttpClient http = mock(HttpClient.class);
        given(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .willAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    String path = request.uri().getPath();
                    String query = request.uri().getQuery();
                    if (path.equals("/user/projects")) {
                        return response("""
                                {"response":{"success":true,"data":{"projects":[
                                  {"projectId":"p-1","title":"첫 프로젝트"},
                                  {"projectId":"p-2","title":"두 번째 프로젝트"}
                                ],"hasNext":false,"lastCursor":-1}}}
                                """);
                    }
                    if (path.equals("/user/posts/projects/p-1/tasks/filter")) {
                        return response("""
                                {"response":{"success":true,"data":{"tasks":[
                                  {"postId":"30001","columns":[{"defaultColumnType":"TASK_NUM","columnData":[{"customColumnData":"757018"}]}]}
                                ]}}}
                                """);
                    }
                    if (path.equals("/user/posts/projects/p-2/tasks/filter")) {
                        assertThat(query).isEqualTo("searchWord=757019");
                        return response("""
                                {"response":{"success":true,"data":{"tasks":[
                                  {"postId":"40001","columns":[{"defaultColumnType":"TASK_NUM","columnData":[{"customColumnData":"757019"}]}]}
                                ]}}}
                                """);
                    }
                    if (path.equals("/user/posts/40001")) {
                        return response("""
                                {"response":{"success":true,"data":{
                                  "postId":"40001","title":"그룹관리자 등록 팝업 수정",
                                  "outContent":"등록 버튼을 추가해 주세요.",
                                  "content":"{\\"COMPS\\":[]}",
                                  "connectUrl":"https://flow.team/l/QECz7","projectTitle":"두 번째 프로젝트",
                                  "attachments":[{"ORCP_FILE_NM":"현재화면.png","FILE_SIZE":"198000","FILE_DOWN_URL":"https://files.example/current.png"}],
                                  "imageAttachments":[{"FILE_NM":"본문이미지.png","IMG_PATH":"https://files.example/body.png"}],
                                  "remarks":[
                                    {"COLABO_REMARK_SRNO":"r-1","RGSR_NM":"김지수","RGSR_ID":"user-1","REMARK_CNTN":"팝업은 유지해 주세요.","RGSN_DTTM":"20260820131700","REPLY_CNT":"2","SYSTEM_REMARK_YN":"N"},
                                    {"COLABO_REMARK_SRNO":"r-2","RGSR_NM":"삭제 사용자","REMARK_CNTN":"삭제 댓글","DELETE_YN":"Y","SYSTEM_REMARK_YN":"N"}
                                  ]
                                }}}
                                """);
                    }
                    throw new AssertionError("예상하지 않은 Flow 호출: " + request.uri());
                });
        var gateway = new HttpFlowPostGateway(
                new FlowProperties("flow-secret", "https://api.flow.team", Duration.ofSeconds(3)),
                new ObjectMapper(), http);

        FlowPost post = gateway.getByTaskNumber("757019");

        assertThat(post.postId()).isEqualTo("40001");
        assertThat(post.title()).isEqualTo("그룹관리자 등록 팝업 수정");
        assertThat(post.content()).isEqualTo("등록 버튼을 추가해 주세요.");
        assertThat(post.projectTitle()).isEqualTo("두 번째 프로젝트");
        assertThat(post.attachments()).containsExactly(
                new FlowPost.Attachment("현재화면.png", 198000L,
                        "https://files.example/current.png", null),
                new FlowPost.Attachment("본문이미지.png", null,
                        "https://files.example/body.png", null));
        assertThat(post.remarks()).containsExactly(
                new FlowPost.Remark("r-1", "김지수", "user-1", "팝업은 유지해 주세요.",
                        "20260820131700", null, 2, false));
        verify(http, times(4)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void 편집기_JSON에서는_CONTENTS만_문서_원문으로_읽는다() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        given(response.statusCode()).willReturn(200);
        given(response.body()).willReturn("""
                {"response":{"success":true,"code":200,"message":"success","data":{
                    "postId":"46578139","title":"긴급 방화벽 교체 작업",
                    "content":"{\\"COMPS\\":[{\\"COMP_TYPE\\":\\"TEXT\\",\\"COMP_DETAIL\\":{\\"CONTENTS\\":\\"첫 번째 안내\\"}},{\\"COMP_TYPE\\":\\"TEXT\\",\\"COMP_DETAIL\\":{\\"CONTENTS\\":\\"두 번째 안내\\"}}]}",
                    "connectUrl":"https://flow.team/l/QECz7"
                }}}
                """);
        given(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .willReturn(response);
        var gateway = new HttpFlowPostGateway(
                new FlowProperties("flow-secret", "https://api.flow.team", Duration.ofSeconds(3)),
                new ObjectMapper(), http);

        FlowPost post = gateway.get("46578139");

        assertThat(post.content()).isEqualTo("첫 번째 안내\n\n두 번째 안내");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void API_키로_게시물_상세를_조회하고_제목과_본문을_읽는다() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        given(response.statusCode()).willReturn(200);
        given(response.body()).willReturn("""
                {"response":{"success":true,"code":200,"message":"success","data":{
                    "postId":"40001","title":"주간 진행 공유",
                    "content":"이번 주 주요 이슈를 공유합니다.",
                    "connectUrl":"https://flow.team/post/40001"
                }}}
                """);
        given(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .willReturn(response);
        var gateway = new HttpFlowPostGateway(
                new FlowProperties("flow-secret", "https://api.flow.team", Duration.ofSeconds(3)),
                new ObjectMapper(), http);

        FlowPost post = gateway.get("40001");

        assertThat(post).isEqualTo(new FlowPost("40001", "주간 진행 공유",
                "이번 주 주요 이슈를 공유합니다.", "https://flow.team/post/40001"));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString())
                .isEqualTo("https://api.flow.team/user/posts/40001");
        assertThat(request.getValue().headers().firstValue("x-flow-api-key"))
                .contains("flow-secret");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static HttpResponse<String> response(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        given(response.statusCode()).willReturn(200);
        given(response.body()).willReturn(body);
        return response;
    }
}
