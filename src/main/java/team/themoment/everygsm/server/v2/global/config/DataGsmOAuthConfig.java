package team.themoment.everygsm.server.v2.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import team.themoment.datagsm.sdk.oauth.DataGsmOAuthClient;

@Configuration
public class DataGsmOAuthConfig {
    @Bean
    public DataGsmOAuthClient dataGsmOAuthClient(@Value("${oauth.datagsm.client-id}") String clientId,
            @Value("${oauth.datagsm.client-secret}") String clientSecret,
            @Value("${oauth.datagsm.authorize-baseurl}") String authorizeBaseUrl,
            @Value("${oauth.datagsm.userinfo-baseurl}") String userInfoBaseUrl) {
        return DataGsmOAuthClient.builder(clientId, clientSecret)
                .authorizationBaseUrl(authorizeBaseUrl)
                .userInfoBaseUrl(userInfoBaseUrl).build();
    }
}
