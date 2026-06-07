package com.example.service;

interface IRuntimeService {
    int startServer(String appId, in List<String> command, in Map envVars, int port, String logFilePath, String codePath);
    void stopServer(String appId);
    void stopAll();
    String getLogs(String logFilePath, int lines);
}
