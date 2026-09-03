package com.bizplay.builder.web;

import com.bizplay.builder.id.IdSequence;
import com.bizplay.builder.project.Project;
import com.bizplay.builder.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 주소에 담긴 프로젝트 번호를 프로젝트로 바꾸고, <b>머리가 쓰는 값들을 한 자리에서</b> 모델에 얹는다.
 *
 * <p>정본: {@code docs/superpowers/specs/2026-08-10-project-context-design.md}
 *
 * <p>⛔ <b>왜 한 자리인가.</b> 머리 조각이 읽는 값이 넷이고 산출물 화면은 최소 열 개다 —
 * 화면마다 손으로 채우면 <b>마흔 자리를 하나도 안 빠뜨려야 한다.</b> 빠뜨렸을 때
 * 프로젝트 이름은 {@link com.bizplay.builder.shell.ShellContract} 가 화면을 깨뜨려서 바로 알지만
 * <b>알림은 조용히 빈 채로 뜬다</b> — 「알림이 안 온다」로 보이지 「코드를 빠뜨렸다」로 안 보인다.
 * 계약이 막겠다고 한 실패 방식 바로 그것이다.
 *
 * <p>⚠ 거는 범위는 {@code /projects/**} 뿐이다({@link com.bizplay.builder.config.WebConfig}).
 * 관리 화면 둘과 로그인 계열 넷은 프로젝트 밖이라 이 자리를 안 거친다.
 */
@Component
public class ProjectContextInterceptor implements HandlerInterceptor {

    /** 찾은 프로젝트를 요청에 담아 두는 이름. preHandle 이 담고 postHandle 이 꺼낸다. */
    static final String ATTRIBUTE_NAME = "projectContext";

    /**
     * {@code /projects/{번호}} 와 그 아래 전부를 잡는다.
     * ⚠ {@code /projects} 자신은 안 잡힌다 — 고르기 화면은 아직 프로젝트가 없는 자리다.
     */
    private static final Pattern PROJECT_ID_PATH = Pattern.compile("^/projects/([^/]+)(?:/.*)?$");

    private final ProjectService projects;

    public ProjectContextInterceptor(ProjectService projects) {
        this.projects = projects;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        Matcher m = PROJECT_ID_PATH.matcher(request.getRequestURI());
        if (!m.matches()) {
            return true;
        }

        Optional<Project> found = readProjectId(m.group(1)).flatMap(projects::findReady);
        if (found.isEmpty()) {
            // 낡은 북마크이거나 지워진 프로젝트다. 500 을 내지 않는다 — 사람이 할 일은 다시 고르는 것뿐이다.
            // ⛔ 표시 이름을 한글로 바꾸지 마라. 이건 HTTP 헤더(Location)로 나가는 값이라
            //    한글이면 서버·브라우저마다 다르게 깨진다. 모델 키도 영문이다(docs/coding-conventions.md).
            response.sendRedirect("/projects?gone");
            return false;
        }

        request.setAttribute(ATTRIBUTE_NAME, found.get());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView mav) {
        if (mav == null || mav.getViewName() == null || mav.getViewName().startsWith("redirect:")) {
            return;
        }
        Project p = (Project) request.getAttribute(ATTRIBUTE_NAME);
        if (p == null) {
            return;
        }

        mav.addObject("projectId", p.getId());
        mav.addObject("projectName", p.getName());
        mav.addObject("projects", projects.ready());

        // ⚠ 알림은 아직 없는 기능이다 — 저장 구조가 V3 까지고 알림 표가 없다.
        //    빈 것을 여기서 얹어 둔다. 알림이 생기면 고치는 파일이 이 하나다.
        mav.addObject("unreadCount", 0);
        mav.addObject("notifications", List.of());
    }

    /**
     * ⛔ <b>숫자로 바꾸지 않는다.</b> 프로젝트 번호는 {@code '0000001'} 꼴의 <b>글자</b>다 —
     * {@code Long.parseLong} 으로 접으면 {@code 1} 이 되어 <b>예외 없이</b> 못 찾고,
     * 위에서 {@code /projects?gone} 으로 튕긴다. 「낡은 북마크」와 구분이 안 되는 조용한 실패다.
     * 일곱 자리 <b>모양만 재고 글자를 그대로</b> 넘긴다.
     */
    private Optional<String> readProjectId(String segment) {
        return IdSequence.isValidId(segment) ? Optional.of(segment) : Optional.empty();
    }
}
