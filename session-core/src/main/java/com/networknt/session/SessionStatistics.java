package com.networknt.session;

import io.undertow.server.session.SessionManagerStatistics;

import java.io.Serializable;
import java.math.BigInteger;

/**
 * attributes for undertow SessionManagerStatistics interface
 */
public class SessionStatistics implements Serializable {
    private long createdSessionCount;
    private long rejectedSessionCount ;
    private long longestSessionLifetime ;
    private long expiredSessionCount;
    private BigInteger totalSessionLifetime = BigInteger.ZERO;
    private long highestSessionCount;
    private  long startTime;

    private static SessionStatistics instance;

    private SessionStatistics() {

    }

    public static SessionStatistics getInstance(){
        return instance;
    }

    public long getCreatedSessionCount() {
        return createdSessionCount;
    }

    public void setCreatedSessionCount(long createdSessionCount) {
        this.createdSessionCount = createdSessionCount;
    }

    public long getRejectedSessionCount() {
        return rejectedSessionCount;
    }

    public void setRejectedSessionCount(long rejectedSessionCount) {
        this.rejectedSessionCount = rejectedSessionCount;
    }

    public long getLongestSessionLifetime() {
        return longestSessionLifetime;
    }

    public void setLongestSessionLifetime(long longestSessionLifetime) {
        this.longestSessionLifetime = longestSessionLifetime;
    }

    public long getExpiredSessionCount() {
        return expiredSessionCount;
    }

    public void setExpiredSessionCount(long expiredSessionCount) {
        this.expiredSessionCount = expiredSessionCount;
    }

    public BigInteger getTotalSessionLifetime() {
        return totalSessionLifetime;
    }

    public void setTotalSessionLifetime(BigInteger totalSessionLifetime) {
        this.totalSessionLifetime = totalSessionLifetime;
    }

    public long getHighestSessionCount() {
        return highestSessionCount;
    }

    public void setHighestSessionCount(long highestSessionCount) {
        this.highestSessionCount = highestSessionCount;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }
}
