package com.RobinNotBad.BiliClient.model;

public class ExpLog {
    public int delta;
    public String time;
    public String reason;

    public ExpLog(int delta, String time, String reason) {
        this.delta = delta;
        this.time = time;
        this.reason = reason;
    }
}

