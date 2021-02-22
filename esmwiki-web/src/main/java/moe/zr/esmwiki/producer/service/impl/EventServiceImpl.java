package moe.zr.esmwiki.producer.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import moe.zr.entry.hekk.PointRanking;
import moe.zr.entry.hekk.ScoreRanking;
import moe.zr.entry.hekk.UserProfile;
import moe.zr.enums.EventRankingNavigationType;
import moe.zr.esmwiki.producer.repository.PointRankingRepository;
import moe.zr.esmwiki.producer.repository.ScoreRankingRepository;
import moe.zr.esmwiki.producer.repository.UserProfileRepository;
import moe.zr.esmwiki.producer.util.CryptoUtils;
import moe.zr.esmwiki.producer.util.RequestUtils;
import moe.zr.qqbot.entry.IMessageQuickReply;
import moe.zr.qqbot.entry.Message;
import moe.zr.service.PointRankingService;
import moe.zr.service.SongRankingService;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.msgpack.MessagePack;
import org.msgpack.type.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

@Service
@Slf4j
public class EventServiceImpl implements IMessageQuickReply {
    final
    RequestUtils requestUtils;
    final
    CloseableHttpAsyncClient httpClient;
    final
    PointRankingRepository pointRankingRepository;
    final
    ScoreRankingRepository scoreRankingRepository;
    final
    PointRankingService pointRankingService;
    final
    SongRankingService songRankingService;
    final
    UserProfileRepository userProfileRepository;
    final
    StringRedisTemplate stringRedisTemplate;


    public EventServiceImpl(CloseableHttpAsyncClient httpClient, PointRankingRepository pointRankingRepository, ScoreRankingRepository scoreRankingRepository, UserProfileRepository userProfileRepository, PointRankingService pointRankingService, SongRankingService songRankingService, ObjectMapper mapper, RequestUtils requestUtils, StringRedisTemplate stringRedisTemplate) {
        this.httpClient = httpClient;
        this.pointRankingRepository = pointRankingRepository;
        this.scoreRankingRepository = scoreRankingRepository;
        this.userProfileRepository = userProfileRepository;
        this.pointRankingService = pointRankingService;
        this.songRankingService = songRankingService;
        this.mapper = mapper;
        this.requestUtils = requestUtils;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private String saveAllRanking() {
        new Thread(() -> {
            try {
                saveAllPointRanking();
                saveAllScoreRanking();
            } catch (BadPaddingException | InterruptedException | ParseException | IOException | ExecutionException | IllegalBlockSizeException e) {
                log.error("发生异常", e);
            }
        }).start();
        return "成功部署任务，可能需要好一会儿";
    }


    final
    ObjectMapper mapper;

    private String initContent(int page) {
        return requestUtils.basicRequest() + "&page=" + page;
    }

    private String initContent(EventRankingNavigationType type) {
        return requestUtils.basicRequest() + "&event_ranking_navigation_type_id=" + type.getRank();
    }

    private void saveAllPointRanking() throws BadPaddingException, InterruptedException, ParseException, IOException, ExecutionException, IllegalBlockSizeException {
        String uri = "https://saki-server.happyelements.cn/get/events/point_ranking";
        JsonNode record = pointRankingService.getRankingRecord(1);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);
        int totalPages = record.get("total_pages").intValue();
        int eventId = record.get("eventId").intValue();
        CountDownLatch latch = new CountDownLatch(10);
        for (int i = 1; i <= totalPages; i++) {
            HttpPost httpPost = requestUtils.buildHttpRequest(uri, initContent(i));
            httpClient.execute(httpPost, new FutureCallback<HttpResponse>() {
                @Override
                @SneakyThrows
                public void completed(HttpResponse httpResponse) {
                    JsonNode jsonNode = countDownAndGet(httpResponse, latch);
                    if (httpResponse.getStatusLine().getStatusCode() != 200) {
                        log.warn("状态码不等于200,返回的正文:{}", jsonNode);
                    } else {
                        ArrayNode rankings = (ArrayNode) jsonNode.get("ranking");
                        ArrayList<UserProfile> userProfiles = new ArrayList<>(20);
                        ArrayList<PointRanking> pointRankings = new ArrayList<>(20);
                        for (JsonNode ranking : rankings) {
                            try {
                                UserProfile userProfile = mapper.treeToValue(ranking.get("user_profile"), UserProfile.class);
                                userProfileRepository.updateByUserId(userProfile);//更新用户信息
                                PointRanking pointRanking = mapper.treeToValue(ranking, PointRanking.class);
                                pointRanking.setEventId(eventId);
                                pointRankings.add(pointRanking);
                                userProfile.setEventId(eventId);
                                userProfiles.add(userProfile);
                            } catch (JsonProcessingException e) {
                                log.warn("发生异常:{}", e.getMessage());
                            }
                        }
                        pointRankingRepository.insert(pointRankings);
                        userProfileRepository.insert(userProfiles);
                    }
                }

                @Override
                public void failed(Exception e) {
                    latch.countDown();
                    System.out.println(e.toString());
                }

                @Override
                public void cancelled() {
                    latch.countDown();
                    System.out.println("can");
                }
            });
        }
        latch.await();
    }


    private void saveAllScoreRanking() throws BadPaddingException, InterruptedException, ParseException, IOException, ExecutionException, IllegalBlockSizeException {
        String uri = "https://saki-server.happyelements.cn/get/events/score_ranking";
        JsonNode record = pointRankingService.getRankingRecord(1);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);
        int totalPages = record.get("total_pages").intValue();
        int eventId = record.get("eventId").intValue();
        CountDownLatch latch = new CountDownLatch(10);
        for (int i = 1; i <= totalPages; i++) {
            HttpPost httpPost = requestUtils.buildHttpRequest(uri, initContent(i));
            httpClient.execute(httpPost, new FutureCallback<HttpResponse>() {
                @Override
                @SneakyThrows
                public void completed(HttpResponse httpResponse) {
                    JsonNode jsonNode = countDownAndGet(httpResponse, latch);
                    if (httpResponse.getStatusLine().getStatusCode() != 200) {
                        log.warn("状态码不等于200,返回的正文:{}", jsonNode);
                    } else {
                        JsonNode rankings = jsonNode.get("ranking");
                        ArrayList<ScoreRanking> scoreRankings = new ArrayList<>(20);
                        rankings.forEach(ranking -> {
                            try {
                                ScoreRanking scoreRanking = mapper.treeToValue(ranking, ScoreRanking.class);
                                scoreRanking.setEventId(eventId);
                                scoreRankings.add(scoreRanking);
                            } catch (JsonProcessingException e) {
                                log.warn("发生异常:{}", e.getMessage());
                            }
                        });
                        scoreRankingRepository.insert(scoreRankings);
                        log.info("成功完成添加");
                    }
                }

                @Override
                public void failed(Exception e) {
                    latch.countDown();
                    System.out.println(e.toString());
                }

                @Override
                public void cancelled() {
                    latch.countDown();
                    System.out.println("can");
                }
            });
        }
        latch.await();
    }

    private JsonNode countDownAndGet(HttpResponse httpResponse, CountDownLatch latch) throws IOException, BadPaddingException, IllegalBlockSizeException {
        latch.countDown();
        byte[] bytes = new byte[50 * 1000];
        BufferedInputStream bufferedInputStream = new BufferedInputStream(httpResponse.getEntity().getContent());
        int read = bufferedInputStream.read(bytes);
        bytes = Arrays.copyOf(bytes, read);
        bufferedInputStream.close();
        Value read1 = new MessagePack().read(CryptoUtils.decrypt(bytes));
        return mapper.readTree(read1.toString());
    }

    @Override
    public String onMessage(String[] str) {
        if (str.length == 2) {
            return saveAllRanking();
        }
        return "?";
    }

    @Override
    public String onMessage(Message message) {
        if (message.getGroupId() != 773891409) {
            return null;
        }
        if (message.getUserId() == 732713726) {
            String[] s = message.getRawMessage().split(" ");
            return onMessage(s);
        }
        return "没有权限";
    }

    @Override
    public String commandPrefix() {
        return "/event";
    }
}
