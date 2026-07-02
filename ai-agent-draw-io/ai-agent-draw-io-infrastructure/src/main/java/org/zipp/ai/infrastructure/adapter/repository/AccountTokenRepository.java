package org.zipp.ai.infrastructure.adapter.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.account.model.entity.AccountToken;
import org.zipp.ai.domain.account.model.valobj.TokenPurpose;
import org.zipp.ai.domain.account.service.IAccountTokenStore;
import org.zipp.ai.infrastructure.dao.IAccountTokenMapper;
import org.zipp.ai.infrastructure.dao.po.AccountTokenPO;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/** DB-backed {@link IAccountTokenStore} (MyBatis). */
@Slf4j
@Repository
public class AccountTokenRepository implements IAccountTokenStore {

    @Resource
    private IAccountTokenMapper accountTokenMapper;

    @Override
    public void insert(AccountToken token) {
        AccountTokenPO po = new AccountTokenPO();
        po.setId(token.getId());
        po.setUserId(token.getUserId());
        po.setPurpose(token.getPurpose().name());
        po.setTokenHash(token.getTokenHash());
        po.setExpiresAt(toDate(token.getExpiresAt()));
        po.setUsedAt(toDate(token.getUsedAt()));
        po.setCreatedAt(toDate(token.getCreatedAt()));
        accountTokenMapper.insert(po);
    }

    @Override
    public Optional<AccountToken> findByHashAndPurpose(String tokenHash, TokenPurpose purpose) {
        return Optional.ofNullable(accountTokenMapper.selectByHashAndPurpose(tokenHash, purpose.name()))
                .map(this::toDomain);
    }

    @Override
    public boolean markUsed(String tokenId, Instant usedAt) {
        return accountTokenMapper.markUsed(tokenId, toDate(usedAt)) == 1;
    }

    private AccountToken toDomain(AccountTokenPO po) {
        return AccountToken.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .purpose(TokenPurpose.valueOf(po.getPurpose()))
                .tokenHash(po.getTokenHash())
                .expiresAt(toInstant(po.getExpiresAt()))
                .usedAt(toInstant(po.getUsedAt()))
                .createdAt(toInstant(po.getCreatedAt()))
                .build();
    }

    private Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
