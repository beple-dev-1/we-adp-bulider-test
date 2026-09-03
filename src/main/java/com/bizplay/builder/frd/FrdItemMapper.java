package com.bizplay.builder.frd;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FrdItemMapper {

    void insert(FrdItem item);

    /** ⚠ 요구사항 원문에서의 차례로 낸다 — 사람이 원문과 나란히 읽는 순서다. */
    List<FrdItem> selectByFrdId(String frdId);

    /** ⛔ 다시 짚으면 통째로 갈아 낀다 — 사람이 손보는 것이 아니라 AI 가 읽은 것의 사본이다. */
    void deleteByFrdId(String frdId);

    /** 화면ID 목록에서 한 화면만 바꾼다. 인터뷰 신규 화면의 TMP 복구에 사용한다. */
    int replaceScreenId(@org.apache.ibatis.annotations.Param("frdId") String frdId,
                        @org.apache.ibatis.annotations.Param("oldScreenId") String oldScreenId,
                        @org.apache.ibatis.annotations.Param("newScreenId") String newScreenId);
}
