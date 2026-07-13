package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.*;

import java.util.List;

@Mapper
public interface IEvalCatalogMapper {
    int insertCaseVersion(EvalCaseVersionPO value);
    EvalCaseVersionPO selectCaseVersion(@Param("caseId") String caseId, @Param("caseVersion") String caseVersion);
    List<EvalCaseVersionPO> selectCaseVersions(@Param("caseId") String caseId);
    int retireCaseVersion(@Param("caseId") String caseId, @Param("caseVersion") String caseVersion,
                          @Param("retiredAt") java.util.Date retiredAt);
    int insertDataset(EvalDatasetPO value);
    EvalDatasetPO selectDataset(@Param("datasetId") String datasetId);
    List<EvalDatasetPO> selectDatasets();
    int insertDatasetVersion(EvalDatasetVersionPO value);
    int updateDatasetVersion(@Param("value") EvalDatasetVersionPO value, @Param("expectedRevision") long expectedRevision);
    EvalDatasetVersionPO selectDatasetVersion(@Param("datasetId") String datasetId, @Param("version") String version);
    List<EvalDatasetVersionPO> selectDatasetVersions(@Param("datasetId") String datasetId);
    int insertDatasetMember(EvalDatasetMemberPO value);
    int deleteDatasetMembers(@Param("datasetId") String datasetId, @Param("version") String version);
    List<EvalDatasetMemberPO> selectDatasetMembers(@Param("datasetId") String datasetId, @Param("version") String version);
}
