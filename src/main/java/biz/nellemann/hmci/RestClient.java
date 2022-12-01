package biz.nellemann.hmci;

import biz.nellemann.hmci.dto.xml.LogonResponse;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class RestClient {

    private final static Logger log = LoggerFactory.getLogger(RestClient.class);
    private final MediaType MEDIA_TYPE_IBM_XML_LOGIN = MediaType.parse("application/vnd.ibm.powervm.web+xml; type=LogonRequest");
    private final MediaType MEDIA_TYPE_IBM_XML_POST = MediaType.parse("application/xml, application/vnd.ibm.powervm.pcm.dita");


    protected OkHttpClient httpClient;

    // OkHttpClient timeouts
    private final static int CONNECT_TIMEOUT = 30;
    private final static int WRITE_TIMEOUT = 30;
    private final static int READ_TIMEOUT = 180;

    protected String authToken;
    protected final String baseUrl;
    protected final String username;
    protected final String password;


    public RestClient(String baseUrl, String username, String password, Boolean trustAll) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
        if (trustAll) {
            this.httpClient = getUnsafeOkHttpClient();
        } else {
            this.httpClient = getSafeOkHttpClient();
        }
    }


    /**
     * Logon to the HMC and get an authentication token for further requests.
     */
    public synchronized void login() {

        log.info("Connecting to HMC - {} @ {}", username, baseUrl);
        StringBuilder payload = new StringBuilder();
        payload.append("<?xml version='1.0' encoding='UTF-8' standalone='yes'?>");
        payload.append("<LogonRequest xmlns='http://www.ibm.com/xmlns/systems/power/firmware/web/mc/2012_10/' schemaVersion='V1_0'>");
        payload.append("<UserID>").append(username).append("</UserID>");
        payload.append("<Password>").append(password).append("</Password>");
        payload.append("</LogonRequest>");

        try {
            //httpClient.start();
            URL url = new URL(String.format("%s/rest/api/web/Logon", baseUrl));
            Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.ibm.powervm.web+xml; type=LogonResponse")
                .addHeader("X-Audit-Memento", "IBM Power HMC Insights")
                .put(RequestBody.create(payload.toString(), MEDIA_TYPE_IBM_XML_LOGIN))
                .build();

            String responseBody;
            try (Response response = httpClient.newCall(request).execute()) {
                responseBody = Objects.requireNonNull(response.body()).string();
                if (!response.isSuccessful()) {
                    log.warn("login() - Unexpected response: {}", response.code());
                    throw new IOException("Unexpected code: " + response);
                }
            }

            XmlMapper xmlMapper = new XmlMapper();
            LogonResponse logonResponse = xmlMapper.readValue(responseBody, LogonResponse.class);

            authToken = logonResponse.getToken();
            log.debug("logon() - auth token: {}", authToken);

        } catch (Exception e) {
            log.warn("logon() - error: {}", e.getMessage());
        }

    }


    /**
     * Logoff from the HMC and remove any session
     *
     */
    synchronized void logoff() {

        if(authToken == null) {
            return;
        }

        try {

            URL url = new URL(String.format("%s/rest/api/web/Logon", baseUrl));
            Request request = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/vnd.ibm.powervm.web+xml; type=LogonRequest")
                .addHeader("X-API-Session", authToken)
                .delete()
                .build();

            String responseBody;
            try (Response response = httpClient.newCall(request).execute()) {
                responseBody = Objects.requireNonNull(response.body()).string();
            } catch (IOException e) {
                log.warn("logoff() error: {}", e.getMessage());
            } finally {
                authToken = null;
            }

        } catch (MalformedURLException e) {
            log.warn("logoff() - error: {}", e.getMessage());
        }

    }


    public String getRequest(String urlPath) throws IOException {
        URL absUrl = new URL(String.format("%s%s", baseUrl, urlPath));
        return getRequest(absUrl);
    }

    public String postRequest(String urlPath, String payload) throws IOException {
        URL absUrl = new URL(String.format("%s%s", baseUrl, urlPath));
        return postRequest(absUrl, payload);
    }


    /**
     * Return a Response from the HMC
     * @param url to get Response from
     * @return Response body string
     */
    public synchronized String getRequest(URL url) throws IOException {

        log.trace("getRequest() - URL: {}", url.toString());

        Request request = new Request.Builder()
            .url(url)
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .addHeader("X-API-Session", (authToken == null ? "" : authToken))
            .get().build();

        String responseBody;
        try (Response response = httpClient.newCall(request).execute()) {

            responseBody = Objects.requireNonNull(response.body()).string();;
            if (!response.isSuccessful()) {

                // Auth. failure
                if(response.code() == 401) {
                    log.warn("getRequest() - 401 - login and retry.");

                    // Let's login again and retry
                    login();
                    return retryGetRequest(url);
                }

                log.error("getRequest() - Unexpected response: {}", response.code());
                throw new IOException("getRequest() - Unexpected response: " + response.code());
            }

        }

        return responseBody;
    }


    private String retryGetRequest(URL url) throws IOException {

        log.debug("retryGetRequest() - URL: {}", url.toString());

        Request request = new Request.Builder()
            .url(url)
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .addHeader("X-API-Session", (authToken == null ? "" : authToken))
            .get().build();

        String responseBody = null;
        try (Response responseRetry = httpClient.newCall(request).execute()) {
            if(responseRetry.isSuccessful()) {
                responseBody = responseRetry.body().string();
            }
        }
        return responseBody;
    }


    /**
     * Send a POST request with a payload (can be null) to the HMC
     * @param url
     * @param payload
     * @return
     * @throws IOException
     */
    public synchronized String postRequest(URL url, String payload) throws IOException {

        log.debug("sendPostRequest() - URL: {}", url.toString());
        RequestBody requestBody;
        if(payload != null) {
            requestBody = RequestBody.create(payload, MEDIA_TYPE_IBM_XML_POST);
        } else {
            requestBody = RequestBody.create("", null);
        }

        Request request = new Request.Builder()
            .url(url)
            .addHeader("content-type", "application/xml")
            .addHeader("X-API-Session", (authToken == null ? "" : authToken) )
            .post(requestBody).build();

        String responseBody;
        try (Response response = httpClient.newCall(request).execute()) {
            responseBody = Objects.requireNonNull(response.body()).string();

            if (!response.isSuccessful()) {
                response.close();
                //log.warn(responseBody);
                log.error("sendPostRequest() - Unexpected response: {}", response.code());
                throw new IOException("sendPostRequest() - Unexpected response: " + response.code());
            }
        }

        return responseBody;
    }


    /**
     * Provide an unsafe (ignoring SSL problems) OkHttpClient
     *
     * @return OkHttpClient ignoring SSL/TLS errors
     */
    private static OkHttpClient getUnsafeOkHttpClient() {
        try {
            // Create a trust manager that does not validate certificate chains
            final TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {  }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[]{};
                    }
                }
            };

            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            // Create a ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager)trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true);
            builder.connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS);
            builder.writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS);
            builder.readTimeout(READ_TIMEOUT, TimeUnit.SECONDS);

            return builder.build();
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Get OkHttpClient with our preferred timeout values.
     * @return OkHttpClient
     */
    private static OkHttpClient getSafeOkHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS);
        builder.writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS);
        builder.readTimeout(READ_TIMEOUT, TimeUnit.SECONDS);
        return builder.build();
    }


}