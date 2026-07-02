package org.zipp.ai.infrastructure.adapter.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.service.IUserAccountStore;
import org.zipp.ai.infrastructure.dao.IUserAccountMapper;
import org.zipp.ai.infrastructure.dao.po.UserAccountPO;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/** DB-backed {@link IUserAccountStore} (MyBatis). */
@Slf4j
@Repository
public class UserAccountRepository implements IUserAccountStore {

    @Resource
    private IUserAccountMapper userAccountMapper;

    @Override
    public Optional<UserAccount> findByEmailNormalized(String emailNormalized) {
        return Optional.ofNullable(userAccountMapper.selectByEmailNormalized(emailNormalized)).map(this::toDomain);
    }

    @Override
    public Optional<UserAccount> findById(String id) {
        return Optional.ofNullable(userAccountMapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public void insert(UserAccount account) {
        UserAccountPO po = new UserAccountPO();
        po.setId(account.getId());
        po.setEmail(account.getEmail());
        po.setEmailNormalized(account.getEmailNormalized());
        po.setPasswordHash(account.getPasswordHash());
        po.setStatus(account.getStatus().name());
        po.setSessionVersion(account.getSessionVersion());
        po.setCreatedAt(toDate(account.getCreatedAt()));
        po.setUpdatedAt(toDate(account.getUpdatedAt()));
        po.setVerifiedAt(toDate(account.getVerifiedAt()));
        userAccountMapper.insert(po);
    }

    @Override
    public void markVerified(String userId, Instant verifiedAt) {
        userAccountMapper.markVerified(userId, toDate(verifiedAt));
    }

    @Override
    public boolean updatePasswordHashAndIncrementSessionVersion(String userId, String passwordHash, Instant updatedAt) {
        return userAccountMapper.updatePasswordHashAndIncrementSessionVersion(
                userId, passwordHash, toDate(updatedAt)) == 1;
    }

    private UserAccount toDomain(UserAccountPO po) {
        return UserAccount.builder()
                .id(po.getId())
                .email(po.getEmail())
                .emailNormalized(po.getEmailNormalized())
                .passwordHash(po.getPasswordHash())
                .status(po.getStatus() == null ? AccountStatus.PENDING_VERIFICATION : AccountStatus.valueOf(po.getStatus()))
                .sessionVersion(po.getSessionVersion() == null ? 0 : po.getSessionVersion())
                .createdAt(toInstant(po.getCreatedAt()))
                .updatedAt(toInstant(po.getUpdatedAt()))
                .verifiedAt(toInstant(po.getVerifiedAt()))
                .deletedAt(toInstant(po.getDeletedAt()))
                .build();
    }

    private Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
