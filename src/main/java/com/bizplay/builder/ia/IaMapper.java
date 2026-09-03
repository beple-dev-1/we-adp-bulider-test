package com.bizplay.builder.ia;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/** IA 데이터 접근. SQL 은 {@code mapper/ia/IaMapper.xml} 에 있다. */
@Mapper
public interface IaMapper {

    Optional<IaStructure> selectStructure(@Param("projectId") String projectId,
                                          @Param("systemCode") String systemCode);

    IaStructure selectStructureById(String id);

    List<IaStructure> selectStructuresByProject(String projectId);

    List<IaRow> selectRows(String structureId);

    List<IaScreenProfile> selectScreenProfiles(String structureId);

    IaRow selectRow(String rowId);

    List<IaScreenLink> selectScreenLinks(String projectId);

    void insertStructure(IaStructure structure);

    void insertRow(IaRow row);

    void insertScreenProfile(IaScreenProfile profile);

    void updateRow(IaRow row);

    void offsetRowOrders(@Param("structureId") String structureId,
                         @Param("offset") int offset);

    void updateRowOrders(@Param("structureId") String structureId,
                         @Param("orders") List<RowOrderUpdate> orders,
                         @Param("updatedBy") String updatedBy);

    void deleteRow(String rowId);

    int deleteStructure(@Param("structureId") String structureId,
                        @Param("expectedVersion") int expectedVersion);

    int selectNextRowOrder(String structureId);

    int bumpVersion(@Param("structureId") String structureId,
                    @Param("expectedVersion") int expectedVersion,
                    @Param("updatedBy") String updatedBy);

    void insertRevision(IaRevision revision);

    void updateStructurePublishing(@Param("structureId") String structureId,
                                   @Param("revision") int revision,
                                   @Param("confirmedBy") String confirmedBy);

    void updateRevisionSuccess(@Param("revisionId") String revisionId,
                               @Param("commit") String commit);

    void updateStructureSuccess(@Param("structureId") String structureId,
                                @Param("commit") String commit);

    void updateRevisionFailure(@Param("revisionId") String revisionId,
                               @Param("failure") String failure);

    void updateStructureFailure(@Param("structureId") String structureId,
                                @Param("failure") String failure);

    record RowOrderUpdate(String rowId, int rowOrder) {}
}
