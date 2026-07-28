package com.fourth.ykd.ai.service;

import com.fourth.ykd.ai.dto.PendingUserFile;
import java.util.Optional;

/** 管理用户当前的文件上下文。参考 ImageContextService。 */
public interface FileContextService {

    void save(String userId, byte[] fileBytes, String fileName);

    Optional<PendingUserFile> findActive(String userId);

    void remove(String userId);
}
