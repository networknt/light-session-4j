package com.networknt.session;

/**
 * Created by gavin on 2017-09-27.
 */
public class SessionManagerConfig {

    String type;
    String deployName;
    int maxSize;


    public SessionManagerConfig() {
    }
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDeployName() {
        return deployName;
    }

    public void setDeployName(String deployName) {
        this.deployName = deployName;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }
}
