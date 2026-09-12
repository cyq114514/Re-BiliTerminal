package com.RobinNotBad.BiliClient.model;

public class LoginRecord {
    public long mid;
    public String deviceName;
    public String loginType;
    public String loginTime;
    public String location;
    public String ip;

    public LoginRecord(long mid, String deviceName, String loginType, String loginTime, String location, String ip) {
        this.mid = mid;
        this.deviceName = deviceName;
        this.loginType = loginType;
        this.loginTime = loginTime;
        this.location = location;
        this.ip = ip;
    }
}

