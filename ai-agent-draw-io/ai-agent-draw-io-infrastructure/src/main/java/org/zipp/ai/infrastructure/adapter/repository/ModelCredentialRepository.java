package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.account.model.entity.ModelCredential;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialStatus;
import org.zipp.ai.domain.account.service.IModelCredentialStore;
import org.zipp.ai.infrastructure.dao.IModelCredentialMapper;
import org.zipp.ai.infrastructure.dao.po.ModelCredentialPO;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** DB-backed model credential store. Raw API keys never enter this adapter. */
@Repository
public class ModelCredentialRepository implements IModelCredentialStore {

    @Resource
    private IModelCredentialMapper modelCredentialMapper;

    @Override
    public void insert(ModelCredential credential) {
        modelCredentialMapper.insert(toPo(credential));
    }

    @Override
    public List<ModelCredential> listByUserId(String userId) {
        if (isBlank(userId)) {
            return Collections.emptyList();
        }
        return modelCredentialMapper.selectByUserId(userId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean disable(String userId, String credentialId, Instant disabledAt) {
        if (isBlank(userId) || isBlank(credentialId)) {
            return false;
        }
        return modelCredentialMapper.disable(userId, credentialId, toDate(disabledAt)) > 0;
    }

    @Override
    public boolean delete(String userId, String credentialId, Instant deletedAt) {
        if (isBlank(userId) || isBlank(credentialId)) {
            return false;
        }
        return modelCredentialMapper.delete(userId, credentialId, toDate(deletedAt)) > 0;
    }

    private ModelCredential toDomain(ModelCredentialPO po) {
        return ModelCredential.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .provider(po.getProvider())
                .baseUrl(po.getBaseUrl())
                .model(po.getModel())
                .completionPath(po.getCompletionPath())
                .displayName(po.getDisplayName())
                .encryptedApiKey(po.getEncryptedApiKey())
                .encryptionProvider(po.getEncryptionProvider())
                .encryptionKeyId(po.getEncryptionKeyId())
                .encryptionNonce(po.getEncryptionNonce())
                .keyLastFour(po.getKeyLastFour())
                .status(po.getStatus() == null ? ModelCredentialStatus.ACTIVE : ModelCredentialStatus.valueOf(po.getStatus()))
                .createdAt(toInstant(po.getCreatedAt()))
                .updatedAt(toInstant(po.getUpdatedAt()))
                .disabledAt(toInstant(po.getDisabledAt()))
                .deletedAt(toInstant(po.getDeletedAt()))
                .build();
    }

    private ModelCredentialPO toPo(ModelCredential credential) {
        ModelCredentialPO po = new ModelCredentialPO();
        po.setId(credential.getId());
        po.setUserId(credential.getUserId());
        po.setProvider(credential.getProvider());
        po.setBaseUrl(credential.getBaseUrl());
        po.setModel(credential.getModel());
        po.setCompletionPath(credential.getCompletionPath());
        po.setDisplayName(credential.getDisplayName());
        po.setEncryptedApiKey(credential.getEncryptedApiKey());
        po.setEncryptionProvider(credential.getEncryptionProvider());
        po.setEncryptionKeyId(credential.getEncryptionKeyId());
        po.setEncryptionNonce(credential.getEncryptionNonce());
        po.setKeyLastFour(credential.getKeyLastFour());
        po.setStatus(credential.getStatus() == null ? ModelCredentialStatus.ACTIVE.name() : credential.getStatus().name());
        po.setCreatedAt(toDate(credential.getCreatedAt()));
        po.setUpdatedAt(toDate(credential.getUpdatedAt()));
        po.setDisabledAt(toDate(credential.getDisabledAt()));
        po.setDeletedAt(toDate(credential.getDeletedAt()));
        return po;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
