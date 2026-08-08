package com.fourth.ykd.ai.memory.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;

/**
 * 创建长期记忆专用的 Redis 向量库
 * Spring AI 1.1.2 默认自动配置不会注册自定义元数据字段，
 * 因此需要在这里明确声明用户、记忆类型、状态和重排数据。
 * 该配置只负责创建向量存储基础设施，
 * 不负责长期记忆写入、同步和召回业务。
 */
//proxyBeanMethods = false 意思是：Spring 不需要为这个配置类创建 CGLIB 代理
@Configuration(proxyBeanMethods = false)
public class MemoryVectorStoreConfiguration {


    /**
     * 创建支持长期记忆元数据过滤的 Redis VectorStore。
     * @param embeddingModel DashScope SDK 向量模型
     * @param properties Spring AI Redis VectorStore 配置
     * @param memoryJedisPooled 完成配置的 Jedis 客户端
     * @return 长期记忆使用的 Redis VectorStore
     */
    @Bean
    public RedisVectorStore memoryRedisVectorStore(
            EmbeddingModel embeddingModel,
            RedisVectorStoreProperties properties,
            JedisPooled memoryJedisPooled
    ) {
        return RedisVectorStore.builder(
                        //数据存到哪个 Redis
                        memoryJedisPooled,
                        //文本使用哪个模型转成向量
                        embeddingModel
                )
                //设置 Redis Search 索引名称
                .indexName(properties.getIndexName())
                .prefix(properties.getPrefix())
                //决定项目启动时是否自动创建 Redis Search 索引结构(ture就是自动创建)
                .initializeSchema(properties.isInitializeSchema())
                //告诉 Redis 以后保存的文档里会有这些元数据字段，并且这些字段需要参与搜索过滤。
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("userId"),
                        //原始 userId 经过 SHA-256 处理后的固定字符串
                        RedisVectorStore.MetadataField.tag("userScope"),
                        RedisVectorStore.MetadataField.tag("memoryId"),
                        RedisVectorStore.MetadataField.tag("memoryType"),
                        RedisVectorStore.MetadataField.tag("memoryKey"),
                        RedisVectorStore.MetadataField.tag("status"),
                        //numeric:表示是数据字段
                        RedisVectorStore.MetadataField.numeric("importance"),
                        RedisVectorStore.MetadataField.numeric("confidence"),
                        RedisVectorStore.MetadataField.numeric("updatedAt")
                )
                .build();
    }


    //负责创建：JedisPooled:一个能够真正连接 Redis，而且内部维护连接池的 Jedis 客户端
    @Bean(destroyMethod = "close")
    public JedisPooled memoryJedisPooled(JedisConnectionFactory connectionFactory){
        return createJedisPooled(connectionFactory);
    }


    /**
     * 真正创建 Jedis:
     * 根据 Spring Boot 已经解析完成的 Redis 配置 创建 Jedis 客户端。
     * 复用 Spring Boot 已解析的 Redis 地址、端口、密码和基础连接参数。
     * @param connectionFactory Spring Boot Redis 连接工厂
     * @return Spring AI Redis VectorStore 使用的 Jedis 客户端
     * 从 JedisConnectionFactory 读取配置
     *                ↓
     * 创建 JedisClientConfig
     *                ↓
     * 创建 HostAndPort
     *                ↓
     * 创建 JedisPooled
     */
    private JedisPooled createJedisPooled(
            JedisConnectionFactory connectionFactory
    ) {
        //JedisClientConfig是接口，表示 Jedis 客户端配置
        JedisClientConfig clientConfig =
                //获取配置构建器
                DefaultJedisClientConfig.builder()
                        .ssl(connectionFactory.isUseSsl())
                        .clientName(connectionFactory.getClientName())
                        //设置 Jedis 超时时间，单位是毫秒
                        .timeoutMillis(connectionFactory.getTimeout())
                        .password(connectionFactory.getPassword())
                        .build();

        //HostAndPor 是：Redis 主机 + Redis 端口
        HostAndPort hostAndPort = new HostAndPort(
                connectionFactory.getHostName(),
                connectionFactory.getPort()
        );

        /*JedisPooled
    ├── 知道 Redis 在哪里
    ├── 知道有没有密码
    ├── 知道是否使用 SSL
    ├── 知道超时时间
    └── 内部维护连接池*/
        return new JedisPooled(
                hostAndPort,
                clientConfig
        );
    }
}