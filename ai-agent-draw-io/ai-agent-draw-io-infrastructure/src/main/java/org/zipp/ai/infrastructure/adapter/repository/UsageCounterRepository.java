package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.account.service.UsageCounterConsumeResult;
import org.zipp.ai.domain.account.service.UsageCounterStore;
import org.zipp.ai.domain.account.service.UsageFailureState;
import org.zipp.ai.infrastructure.dao.IUsageCounterMapper;
import org.zipp.ai.infrastructure.dao.po.UsageCounterPO;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;

@Repository
public class UsageCounterRepository implements UsageCounterStore {

    private static final String TYPE_COUNTER = "COUNTER";
    private static final String TYPE_FAILURE = "FAILURE";
    private static final Instant NEVER_EXPIRES = Instant.parse("9999-12-31T23:59:59Z");

    @Resource
    private IUsageCounterMapper usageCounterMapper;

    @Override
    @Transactional
    public UsageCounterConsumeResult consume(String key, int limit, Instant expiresAt, Instant now) {
        validateCounter(limit, expiresAt, now);
        String keyHash = hash(key);
        Date nowDate = toDate(now);
        usageCounterMapper.insertEmptyIfAbsent(keyHash, preview(keyHash), TYPE_COUNTER, nowDate, nowDate);

        UsageCounterPO counter = usageCounterMapper.selectByKeyHashForUpdate(keyHash);
        boolean expired = isExpired(counter.getExpiresAt(), now);
        int current = expired ? 0 : countValue(counter);
        if (current >= limit) {
            return UsageCounterConsumeResult.rejected(current);
        }

        int next = current + 1;
        counter.setCounterType(TYPE_COUNTER);
        counter.setCountValue(next);
        counter.setExpiresAt(expired ? toDate(expiresAt) : counter.getExpiresAt());
        counter.setLockedUntil(null);
        counter.setUpdatedAt(nowDate);
        usageCounterMapper.updateCounter(counter);
        return UsageCounterConsumeResult.consumed(next);
    }

    @Override
    public int count(String key, Instant now) {
        if (now == null) {
            return 0;
        }
        UsageCounterPO counter = usageCounterMapper.selectByKeyHash(hash(key));
        if (counter == null || isExpired(counter.getExpiresAt(), now)) {
            return 0;
        }
        return countValue(counter);
    }

    @Override
    @Transactional
    public UsageFailureState recordFailure(String key, int maxFailures, Instant lockUntil, Instant now) {
        validateFailure(maxFailures, lockUntil, now);
        String keyHash = hash(key);
        Date nowDate = toDate(now);
        usageCounterMapper.insertEmptyIfAbsent(keyHash, preview(keyHash), TYPE_FAILURE, nowDate, nowDate);

        UsageCounterPO counter = usageCounterMapper.selectByKeyHashForUpdate(keyHash);
        Instant activeLock = activeLock(counter, now);
        if (activeLock != null) {
            return UsageFailureState.of(countValue(counter), activeLock);
        }

        int current = isExpired(counter.getExpiresAt(), now) ? 0 : countValue(counter);
        int next = current + 1;
        Instant newLockedUntil = next >= maxFailures ? lockUntil : null;
        counter.setCounterType(TYPE_FAILURE);
        counter.setCountValue(next);
        counter.setExpiresAt(toDate(newLockedUntil == null ? NEVER_EXPIRES : newLockedUntil));
        counter.setLockedUntil(toDate(newLockedUntil));
        counter.setUpdatedAt(nowDate);
        usageCounterMapper.updateCounter(counter);
        return UsageFailureState.of(next, newLockedUntil);
    }

    @Override
    public UsageFailureState failureState(String key, Instant now) {
        if (now == null) {
            return UsageFailureState.of(0, null);
        }
        UsageCounterPO counter = usageCounterMapper.selectByKeyHash(hash(key));
        if (counter == null || isExpired(counter.getExpiresAt(), now)) {
            return UsageFailureState.of(0, null);
        }
        return UsageFailureState.of(countValue(counter), activeLock(counter, now));
    }

    @Override
    public void clear(String key) {
        usageCounterMapper.deleteByKeyHash(hash(key));
    }

    private void validateCounter(int limit, Instant expiresAt, Instant now) {
        if (limit <= 0 || expiresAt == null || now == null || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("counter settings must be positive");
        }
    }

    private void validateFailure(int maxFailures, Instant lockUntil, Instant now) {
        if (maxFailures <= 0 || lockUntil == null || now == null || !lockUntil.isAfter(now)) {
            throw new IllegalArgumentException("failure settings must be positive");
        }
    }

    private boolean isExpired(Date expiresAt, Instant now) {
        return expiresAt == null || !now.isBefore(expiresAt.toInstant());
    }

    private Instant activeLock(UsageCounterPO counter, Instant now) {
        if (counter == null || counter.getLockedUntil() == null) {
            return null;
        }
        Instant lockedUntil = counter.getLockedUntil().toInstant();
        return now.isBefore(lockedUntil) ? lockedUntil : null;
    }

    private int countValue(UsageCounterPO counter) {
        return counter.getCountValue() == null ? 0 : Math.max(0, counter.getCountValue());
    }

    private Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private String preview(String keyHash) {
        return keyHash.substring(0, Math.min(16, keyHash.length()));
    }

    private String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(String.valueOf(key).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(encoded.length * 2);
            for (byte value : encoded) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
