package com.bizplay.builder.checker;

import java.nio.file.Path;

/**
 * 기획 레포 안에 실려 온 검사기를 <b>한 번 돌린다.</b>
 *
 * <p>⛔ <b>빌더가 자기 검사기를 만들지 않는다.</b> 규격을 정한 쪽(추출기)이 검사기를 갖고,
 * 그 사본이 기획 레포에 실려 온다. 빌더가 따로 만들면 <b>「뭐가 맞는지」가 두 벌</b>이 되어
 * 기획팀은 초록인데 빌더는 빨강인 상황이 생기고, 그때 누가 맞는지 정할 방법이 없다.
 * <b>여기가 하는 일은 「부르는 것」뿐이다.</b>
 *
 * <p>테스트는 여기에 대역을 낀다 — 판정 로직을 node 없이 재려고 끊어 둔 자리다.
 */
public interface CheckerCommand {

    /**
     * ⭐ <b>자리가 둘인 것이 실측의 결과다 (2026-08-14).</b> 검사기 의존({@code node_modules})은
     * <b>커밋 대상이 아니라 워크트리에 안 딸려온다.</b> 그래서 워크트리의 검사기를 부르면
     * <b>검사마다 {@code npm install} 을 새로 하게 된다.</b>
     * <b>클론에 한 번 깔아 두고 그 검사기로 워크트리를 검사하면 된다</b> — 실측 951ms 로 확인했다.
     *
     * @param checkerHome 검사기가 <b>설치된</b> 자리(= 공용 클론). 여기 {@code verify/node_modules} 가 산다
     * @param repoRoot    <b>검사할</b> 뿌리(= 이 검사 전용 워크트리). ⛔ 검사기는 이 아래 전체를 본다 —
     *                    한 파일만 보는 입구는 아직 없어서, 「누구 탓인가」는
     *                    {@link DraftChecker} 가 전·후를 견줘 가른다
     * @return 못 돌렸으면 {@link CheckReport#unknown()}. ⛔ 던지지 말고 그것으로 낸다 —
     *         「못 돌렸다」는 예외 상황이 아니라 <b>세 번째 판정</b>이다
     */
    CheckReport run(Path checkerHome, Path repoRoot);
}
