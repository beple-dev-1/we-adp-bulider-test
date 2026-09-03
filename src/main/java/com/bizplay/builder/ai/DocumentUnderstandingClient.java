package com.bizplay.builder.ai;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 서버가 글자를 못 뽑는 문서를 <b>멀티모달 AI 가 대신 읽는다.</b>
 *
 * <p>⛔ <b>공급자를 부르는 쪽에 결합하지 마라.</b> 지금 구현은 Gemini 하나지만
 * ({@link GeminiDocumentUnderstanding}) 값과 속도를 재 보고 갈아탈 수 있다.
 * 부르는 쪽은 이 인터페이스만 본다.
 *
 * <p>⛔ <b>이 층은 요약도 요구사항 생성도 하지 않는다.</b> 하는 일은 넷뿐이다 —
 * ① 문서의 글자를 뽑고 ② 표와 제목 구조를 되살리고 ③ 그림 안의 업무 정보를 옮겨 적고
 * ④ 못 읽은 자리를 <b>못 읽었다고 표시</b>한다. <b>없는 내용을 지어내지 않는다.</b>
 * 요약을 시키면 뒤의 요구사항이 통째로 그 위에 선다.
 *
 * <p>결과는 문서 내용으로 바로 저장되며, 분석이 끝나면 요구사항 분석을 시작할 수 있다.
 * 읽지 못한 자리는 명시적으로 표시하고 없는 내용을 지어내지 않는다.
 */
public interface DocumentUnderstandingClient {

    /**
     * 설정이 앉아 있어 실제로 부를 수 있나.
     *
     * <p>⛔ <b>거짓일 때 부르지 마라</b> — 부르는 쪽이 먼저 이것을 보고 사람에게
     * 「내용 분석 설정이 없다」로 말해야 한다. 안 그러면 설정이 없는 것과 저쪽이 터진 것이
     * 같은 오류로 뭉쳐 <b>고칠 자리를 못 찾는다.</b>
     */
    boolean available();

    /**
     * 파일 하나를 읽어 글로 돌려준다.
     *
     * @param file      읽을 원본. ⛔ <b>안 고친다</b> — 원본 보존이 규칙이다
     * @param mediaType 저쪽에 알려 줄 종류({@code application/pdf}·{@code image/png} …)
     * @return 뽑아낸 글. <b>빈 글자를 돌려주지 않는다</b> — 못 읽었으면 던진다
     * @throws IOException 설정이 없거나 · 파일이 너무 크거나 · 저쪽이 거절했거나 · 글자가 안 나왔을 때.
     *         ⛔ <b>빈 글자를 성공으로 넘기지 마라</b> — 그것이 이 저장소가 두 번 데인 자리다
     */
    String read(Path file, String mediaType) throws IOException;
}
