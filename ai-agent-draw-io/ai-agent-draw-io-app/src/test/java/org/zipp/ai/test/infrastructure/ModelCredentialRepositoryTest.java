package org.zipp.ai.test.infrastructure;

import org.junit.Test;
import org.zipp.ai.domain.account.model.entity.ModelCredential;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialStatus;
import org.zipp.ai.infrastructure.adapter.repository.ModelCredentialRepository;
import org.zipp.ai.infrastructure.dao.IModelCredentialMapper;
import org.zipp.ai.infrastructure.dao.po.ModelCredentialPO;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ModelCredentialRepositoryTest {

    @Test
    public void shouldInsertEncryptedCredentialMetadataWithoutRawApiKey() throws Exception {
        ModelCredentialRepository repository = new ModelCredentialRepository();
        FakeModelCredentialMapper mapper = new FakeModelCredentialMapper();
        injectMapper(repository, mapper);

        repository.insert(ModelCredential.builder()
                .id("mcr_1")
                .userId("usr_alice")
                .provider("openai")
                .baseUrl("https://api.openai.com/v1")
                .model("gpt-4o")
                .completionPath("/chat/completions")
                .displayName("OpenAI production")
                .encryptedApiKey("ciphertext")
                .encryptionProvider("ENV_AES_GCM")
                .encryptionKeyId("env-key-1")
                .encryptionNonce("nonce")
                .keyLastFour("1234")
                .status(ModelCredentialStatus.ACTIVE)
                .createdAt(Instant.parse("2026-07-02T10:00:00Z"))
                .updatedAt(Instant.parse("2026-07-02T10:00:00Z"))
                .build());

        assertEquals("mcr_1", mapper.inserted.getId());
        assertEquals("usr_alice", mapper.inserted.getUserId());
        assertEquals("ciphertext", mapper.inserted.getEncryptedApiKey());
        assertEquals("ENV_AES_GCM", mapper.inserted.getEncryptionProvider());
        assertEquals("env-key-1", mapper.inserted.getEncryptionKeyId());
        assertEquals("nonce", mapper.inserted.getEncryptionNonce());
        assertEquals("1234", mapper.inserted.getKeyLastFour());
    }

    @Test
    public void shouldListByOwnerAndMapDomainFields() throws Exception {
        ModelCredentialRepository repository = new ModelCredentialRepository();
        FakeModelCredentialMapper mapper = new FakeModelCredentialMapper();
        injectMapper(repository, mapper);

        List<ModelCredential> result = repository.listByUserId("usr_alice");

        assertEquals("usr_alice", mapper.listedUserId);
        assertEquals(1, result.size());
        assertEquals("mcr_1", result.get(0).getId());
        assertEquals(ModelCredentialStatus.DISABLED, result.get(0).getStatus());
        assertEquals("1234", result.get(0).getKeyLastFour());
    }

    @Test
    public void shouldFindCredentialByOwnerBeforeChatResolution() throws Exception {
        ModelCredentialRepository repository = new ModelCredentialRepository();
        FakeModelCredentialMapper mapper = new FakeModelCredentialMapper();
        injectMapper(repository, mapper);

        ModelCredential credential = repository.findByUserIdAndId("usr_alice", "mcr_1").orElseThrow();

        assertEquals("usr_alice", mapper.selectedUserId);
        assertEquals("mcr_1", mapper.selectedId);
        assertEquals("usr_alice", credential.getUserId());
        assertEquals("mcr_1", credential.getId());
    }

    @Test
    public void shouldDisableAndDeleteWithOwnerConstraint() throws Exception {
        ModelCredentialRepository repository = new ModelCredentialRepository();
        FakeModelCredentialMapper mapper = new FakeModelCredentialMapper();
        injectMapper(repository, mapper);
        Instant now = Instant.parse("2026-07-02T10:00:00Z");

        assertTrue(repository.disable("usr_alice", "mcr_1", now));
        assertEquals("usr_alice", mapper.disabledUserId);
        assertEquals("mcr_1", mapper.disabledId);

        assertTrue(repository.delete("usr_alice", "mcr_1", now));
        assertEquals("usr_alice", mapper.deletedUserId);
        assertEquals("mcr_1", mapper.deletedId);
    }

    @Test
    public void mapperXmlKeepsMutationsScopedToUserId() throws Exception {
        String mapperXml = new String(getClass()
                .getResourceAsStream("/mybatis/mapper/model_credential_mapper.xml")
                .readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertTrue(mapperXml.contains("encrypted_api_key"));
        assertTrue(mapperXml.contains("key_last_four"));
        assertTrue(mapperXml.contains("WHERE user_id = #{userId} AND id = #{id} AND deleted_at IS NULL"));
        assertTrue(mapperXml.contains("WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL"));
        assertTrue(mapperXml.contains("encrypted_api_key = ''"));
        assertTrue(mapperXml.contains("user_id = #{anonymizedUserId}"));
        assertTrue(mapperXml.contains("deleted_at = COALESCE(deleted_at, #{deletedAt})"));
        assertTrue(mapperXml.contains("updated_at = #{deletedAt} WHERE user_id = #{userId}"));
    }

    private void injectMapper(ModelCredentialRepository repository, IModelCredentialMapper mapper) throws Exception {
        Field field = ModelCredentialRepository.class.getDeclaredField("modelCredentialMapper");
        field.setAccessible(true);
        field.set(repository, mapper);
    }

    private static final class FakeModelCredentialMapper implements IModelCredentialMapper {
        private ModelCredentialPO inserted;
        private String listedUserId;
        private String disabledUserId;
        private String disabledId;
        private String deletedUserId;
        private String deletedId;
        private String selectedUserId;
        private String selectedId;

        @Override
        public int insert(ModelCredentialPO credential) {
            inserted = credential;
            return 1;
        }

        @Override
        public List<ModelCredentialPO> selectByUserId(String userId) {
            listedUserId = userId;
            ModelCredentialPO po = new ModelCredentialPO();
            po.setId("mcr_1");
            po.setUserId(userId);
            po.setProvider("openai");
            po.setBaseUrl("https://api.openai.com/v1");
            po.setModel("gpt-4o");
            po.setCompletionPath("/chat/completions");
            po.setDisplayName("OpenAI production");
            po.setEncryptedApiKey("ciphertext");
            po.setEncryptionProvider("ENV_AES_GCM");
            po.setEncryptionKeyId("env-key-1");
            po.setEncryptionNonce("nonce");
            po.setKeyLastFour("1234");
            po.setStatus("DISABLED");
            po.setCreatedAt(Date.from(Instant.parse("2026-07-02T10:00:00Z")));
            po.setUpdatedAt(Date.from(Instant.parse("2026-07-02T10:00:00Z")));
            po.setDisabledAt(Date.from(Instant.parse("2026-07-02T10:05:00Z")));
            return List.of(po);
        }

        @Override
        public ModelCredentialPO selectByUserIdAndId(String userId, String id) {
            selectedUserId = userId;
            selectedId = id;
            return selectByUserId(userId).stream()
                    .filter(po -> po.getId().equals(id))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public int disable(String userId, String id, Date disabledAt) {
            disabledUserId = userId;
            disabledId = id;
            return 1;
        }

        @Override
        public int delete(String userId, String id, Date deletedAt) {
            deletedUserId = userId;
            deletedId = id;
            return 1;
        }

        @Override
        public int deleteAllForUser(String userId, String anonymizedUserId, Date deletedAt) {
            deletedUserId = userId;
            return 1;
        }
    }
}
