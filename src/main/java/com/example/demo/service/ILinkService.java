package com.example.demo.service;

import com.example.demo.control.CommandHandler;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Service
public class ILinkService {

    private static final Logger log = LoggerFactory.getLogger(ILinkService.class);
    private static final String WEIXIN_HOST = "ilinkai.weixin.qq.com";
    private static final int WEIXIN_PORT = 443;

    private ILinkClient client;
    private LoginContext loginContext;
    private final CountDownLatch loginLatch = new CountDownLatch(1);

    @Autowired
    private CommandHandler commandHandler;

    @PostConstruct
    public void init() {
        // 网络预检（快速同步执行）
        if (!networkPrecheck()) {
            log.warn("网络预检未通过，跳过 iLink 客户端初始化");
            return;
        }

        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(35000)
                .readTimeoutMs(35000)
                .writeTimeoutMs(35000)
                .httpMaxRetries(3)
                .retryBaseDelayMs(1000)
                .retryMaxDelayMs(10000)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(30000)
                .channelVersion("1.0.0")
                .build();

        client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        loginContext = context;
                        loginLatch.countDown();
                        log.info("登录成功，botId = {}", context.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        loginLatch.countDown();
                        log.error("登录失败: {}", resolveRootCauseMessage(throwable));
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        for (WeixinMessage msg : messages) {
                            handleIncomingMessage(msg);
                        }
                    }
                })
                .build();

        // 异步执行二维码登录，不阻塞主线程（CLI / Web 可立即使用）
        Thread loginThread = new Thread(this::doLogin, "ilink-login");
        loginThread.setDaemon(true);
        loginThread.start();
    }

    private void doLogin() {
        try {
            String qrCodeContent = client.executeLogin();
            log.info("请扫码登录，二维码内容已输出");
            System.out.println("========== 请使用微信扫描下方二维码登录 ==========");
            System.out.println(qrCodeContent);
            System.out.println("================================================");

            loginContext = client.getLoginFuture().get(120, TimeUnit.SECONDS);
            log.info("登录完成，botId = {}", loginContext.getBotId());

            // 登录后拉取一次未读消息
            List<WeixinMessage> messages = client.getUpdates();
            log.info("首次拉取消息数 = {}", messages.size());
            for (WeixinMessage msg : messages) {
                handleIncomingMessage(msg);
            }
        } catch (Exception e) {
            log.error("初始化 iLink 客户端失败: {}", resolveRootCauseMessage(e));
        }
    }

    /**
     * 网络预检：DNS 解析 + TCP/TLS 握手探测 + 代理检测
     */
    private boolean networkPrecheck() {
        log.info("网络预检开始...");

        // 1. DNS 解析
        try {
            InetAddress[] addresses = InetAddress.getAllByName(WEIXIN_HOST);
            log.info("DNS 解析成功: {} -> {}", WEIXIN_HOST, (Object) addresses);
        } catch (UnknownHostException e) {
            log.error("DNS 解析失败: {}，请检查网络连接或 DNS 配置", WEIXIN_HOST);
            return false;
        }

        // 2. TCP 连接探测
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(WEIXIN_HOST, WEIXIN_PORT), 5000);
            log.info("TCP 连接成功: {}:{}", WEIXIN_HOST, WEIXIN_PORT);
        } catch (ConnectException e) {
            log.error("TCP 连接被拒绝: {}:{}，可能被防火墙拦截", WEIXIN_HOST, WEIXIN_PORT);
            return false;
        } catch (Exception e) {
            log.error("TCP 连接异常: {}", e.getMessage());
            return false;
        }

        // 3. 代理检测
        String httpProxy = System.getProperty("http.proxyHost");
        String httpsProxy = System.getProperty("https.proxyHost");
        if (httpProxy != null || httpsProxy != null) {
            log.info("检测到 HTTP 代理: {} / HTTPS 代理: {}", httpProxy, httpsProxy);
        } else {
            log.info("未检测到代理设置");
        }

        log.info("网络预检通过");
        return true;
    }

    /**
     * 处理收到的消息
     */
    private void handleIncomingMessage(WeixinMessage msg) {
        log.info("收到消息 fromUserId = {}", msg.getFrom_user_id());
        if (msg.getItem_list() != null) {
            for (MessageItem item : msg.getItem_list()) {
                if (item.getText_item() != null) {
                    String text = item.getText_item().getText();
                    log.info("收到文本消息: from={}, text={}", msg.getFrom_user_id(), text);
                    System.out.println("[" + msg.getFrom_user_id() + "] 说: " + text);

                    // 通过 CommandManager 匹配命令
                    String reply = commandHandler.handle(text, msg.getFrom_user_id());
                    if (reply != null) {
                        // 先显示"对方正在输入..."，再发送回复
                        sendTextWithTyping(msg.getFrom_user_id(), reply, 1500);
                    }
                }
            }
        }
    }

    /**
     * 发送文本消息
     */
    public void sendText(String targetUserId, String text) {
        if (client == null || loginContext == null) {
            log.warn("客户端未就绪，无法发送消息");
            return;
        }
        try {
            client.sendText(targetUserId, text);
            log.info("文本消息发送成功: to={}, text={}", targetUserId, text);
        } catch (Exception e) {
            log.error("发送文本消息失败: {}", resolveRootCauseMessage(e));
        }
    }

    /**
     * 发送带输入态的文本消息
     */
    public void sendTextWithTyping(String targetUserId, String text, long typingMs) {
        if (client == null || loginContext == null) {
            log.warn("客户端未就绪，无法发送消息");
            return;
        }
        try {
            client.sendTextWithTyping(targetUserId, text, typingMs);
            log.info("带输入态文本消息发送成功: to={}, text={}", targetUserId, text);
        } catch (Exception e) {
            log.error("发送带输入态文本消息失败: {}", resolveRootCauseMessage(e));
        }
    }

    /**
     * 发送图片消息
     * @param targetUserId 目标用户 ID
     * @param imageBytes   图片字节数据
     * @param fileName     文件名（如 "image.jpg"）
     * @param caption      图片说明文字（可选，传 null 或空则不发送）
     */
    public void sendImage(String targetUserId, byte[] imageBytes, String fileName, String caption) {
        if (client == null || loginContext == null) {
            log.warn("客户端未就绪，无法发送图片");
            return;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("图片数据为空，取消发送");
            return;
        }
        try {
            client.sendImage(targetUserId, imageBytes, fileName, caption);
            log.info("图片消息发送成功: to={}, fileName={}, size={}bytes", targetUserId, fileName, imageBytes.length);
        } catch (Exception e) {
            log.error("发送图片消息失败: {}", resolveRootCauseMessage(e));
        }
    }
    

    /**
     * 等待登录完成
     */
    public boolean awaitLogin(long timeout, TimeUnit unit) throws InterruptedException {
        return loginLatch.await(timeout, unit);
    }

    /**
     * 获取登录上下文
     */
    public LoginContext getLoginContext() {
        return loginContext;
    }

    /**
     * 获取 botId
     */
    public String getBotId() {
        return loginContext != null ? loginContext.getBotId() : null;
    }

    /**
     * 异常链根因提取 — 针对不同异常给出可操作排障建议
     */
    public static String resolveRootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cause.getClass().getSimpleName();
        }

        if (cause instanceof UnknownHostException) {
            return "DNS 解析失败，无法连接到服务器。请检查网络连接或 DNS 配置。(" + msg + ")";
        }
        if (cause instanceof ConnectException) {
            return "连接被拒绝，可能被防火墙拦截或服务端未启动。(" + msg + ")";
        }
        if (cause instanceof java.net.SocketTimeoutException) {
            return "连接超时，网络不稳定或服务端响应慢。(" + msg + ")";
        }
        if (cause instanceof javax.net.ssl.SSLException) {
            return "SSL/TLS 握手失败，可能是中间人代理或证书问题。(" + msg + ")";
        }
        return msg;
    }

    @PreDestroy
    public void destroy() {
        if (client != null) {
            client.close();
            log.info("iLink 客户端已关闭");
        }
    }
}
