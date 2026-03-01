package com.pnones.trace.model;

import java.util.List;
import java.util.Map;

public class SqlTraceRecord {
    private String sql;
    private long elapsedMs;
    private Object result;
    private int resultRowCount = 0;
    private java.util.Map<Integer,Object> params;
    private String caller;
    private List<String> callerStack;
    private String requestId;
    private long threadId;

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    public Object getResult() { return result; }
    public void setResult(Object result) { this.result = result; }
    public String getCaller() { return caller; }
    public void setCaller(String caller) { this.caller = caller; }
    public List<String> getCallerStack() { return callerStack; }
    public void setCallerStack(List<String> callerStack) { this.callerStack = callerStack; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public long getThreadId() { return threadId; }
    public void setThreadId(long threadId) { this.threadId = threadId; }

    public java.util.Map<Integer,Object> getParams() { return params; }
    public void setParams(java.util.Map<Integer,Object> params) { this.params = params; }
    public int getResultRowCount() { return resultRowCount; }
    public void setResultRowCount(int resultRowCount) { this.resultRowCount = resultRowCount; }
}
