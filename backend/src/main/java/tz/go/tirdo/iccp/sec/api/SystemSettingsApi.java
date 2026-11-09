package tz.go.tirdo.iccp.sec.api;

public interface SystemSettingsApi {
    boolean enabled(String code);
    boolean booleanValue(String code,boolean fallback);
}
