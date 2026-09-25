package com.strawing.duckmock;

interface IMockService {
    int getVersion();
    Bundle getState();
    void pushConfig(in Bundle config);
    List<Bundle> getRecords();
    void clearRecords();
}
