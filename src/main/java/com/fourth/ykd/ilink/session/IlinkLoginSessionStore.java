package com.fourth.ykd.ilink.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourth.ykd.ilink.config.IlinkProperties;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

@Component
public class IlinkLoginSessionStore {

    private final Path sessionPath;
    private final ObjectMapper objectMapper;

    public IlinkLoginSessionStore(IlinkProperties properties, ObjectMapper objectMapper) {
        this.sessionPath = Path.of(properties.getSessionFile());
        this.objectMapper = objectMapper;
    }

    public synchronized LoginContext load() throws IOException {
        if (!Files.exists(sessionPath)) {
            return null;
        }
        IlinkLoginSession session = objectMapper.readValue(sessionPath.toFile(), IlinkLoginSession.class);
        return new LoginContext(session.botToken(), session.userId(), session.botId(), session.baseUrl());
    }

    public synchronized void save(LoginContext loginContext) throws IOException {
        Path parent = sessionPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = sessionPath.resolveSibling(sessionPath.getFileName() + ".tmp");
        objectMapper.writeValue(tempFile.toFile(), new IlinkLoginSession(
                loginContext.getBotToken(), loginContext.getUserId(),
                loginContext.getBotId(), loginContext.getBaseUrl()));
        Files.move(tempFile, sessionPath, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
