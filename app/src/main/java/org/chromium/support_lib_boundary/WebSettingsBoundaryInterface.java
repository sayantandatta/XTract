package org.chromium.support_lib_boundary;

import java.util.Map;
import java.util.Set;

public interface WebSettingsBoundaryInterface {
    void setRequestedWithHeaderOriginAllowList(Set<String> allowedOriginRules);

    Set<String> getRequestedWithHeaderOriginAllowList();

    Map<String, Object> getUserAgentMetadataMap();

    void setUserAgentMetadataMap(Map<String, Object> uaMetadata);
}
