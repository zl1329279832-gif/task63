package com.controller;

import com.entity.*;
import com.service.*;
import com.utils.R;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ShangpinOrderController 单元测试
 * 覆盖：并发下单防超卖、非法状态跳转拦截、退款校验、积分支付拒绝
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShangpinOrderControllerTest {

    @InjectMocks
    private ShangpinOrderController controller;

    @Mock private ShangpinOrderService shangpinOrderService;
    @Mock private ShangpinService shangpinService;
    @Mock private YonghuService yonghuService;
    @Mock private ShangjiaService shangjiaService;
    @Mock private CartService cartService;
    @Mock private TokenService tokenService;
    @Mock private DictionaryService dictionaryService;
    @Mock private ForumService forumService;
    @Mock private GuanggaoService guanggaoService;
    @Mock private NewsService newsService;
    @Mock private ShangpinCollectionService shangpinCollectionService;
    @Mock private ShangpinCommentbackService shangpinCommentbackService;
    @Mock private UsersService usersService;

    private MockHttpServletRequest request;
    private ShangpinEntity shangpin;
    private YonghuEntity yonghu;
    private ShangjiaEntity shangjia;

    @BeforeEach
    void setUp() {
        // 模拟 session
        request = new MockHttpServletRequest();
        HttpSession session = request.getSession();
        session.setAttribute("userId", 1);
        session.setAttribute("role", "用户");

        // 商品：价格10，库存5
        shangpin = new ShangpinEntity();
        shangpin.setId(100);
        shangpin.setShangpinName("测试菜品");
        shangpin.setShangpinNewMoney(10.0);
        shangpin.setShangpinKucunNumber(5);
        shangpin.setShangjiaId(200);

        // 用户：余额100
        yonghu = new YonghuEntity();
        yonghu.setId(1);
        yonghu.setNewMoney(100.0);

        // 店家：余额50
        shangjia = new ShangjiaEntity();
        shangjia.setId(200);
        shangjia.setNewMoney(50.0);
    }

    // ============== /add 下单测试 ==============

    @Nested
    @DisplayName("单品下单 /add")
    class AddTests {

        private ShangpinOrderEntity buildOrder(int buyNumber) {
            ShangpinOrderEntity order = new ShangpinOrderEntity();
            order.setShangpinId(100);
            order.setBuyNumber(buyNumber);
            order.setShangpinOrderPaymentTypes(1);
            return order;
        }

        @Test
        @DisplayName("正常下单成功")
        void add_success() {
            when(shangpinService.selectById(100)).thenReturn(shangpin);
            when(yonghuService.selectById(1)).thenReturn(yonghu);
            when(shangjiaService.selectById(200)).thenReturn(shangjia);
            when(shangpinService.update(any(ShangpinEntity.class), any(EntityWrapper.class))).thenReturn(true);

            R result = controller.add(buildOrder(2), request);

            assertEquals(0, result.get("code"));
            verify(shangpinOrderService).insert(any(ShangpinOrderEntity.class));
            verify(shangpinService).update(any(ShangpinEntity.class), any(EntityWrapper.class));
        }

        @Test
        @DisplayName("积分支付被拒绝")
        void add_rejects_points_payment() {
            ShangpinOrderEntity order = buildOrder(1);
            order.setShangpinOrderPaymentTypes(2);

            R result = controller.add(order, request);

            assertEquals(511, result.get("code"));
            assertTrue(result.get("msg").toString().contains("积分"));
            verify(shangpinOrderService, never()).insert(any());
        }

        @Test
        @DisplayName("库存不足返回错误")
        void add_insufficient_stock() {
            shangpin.setShangpinKucunNumber(1); // 只有1个库存
            when(shangpinService.selectById(100)).thenReturn(shangpin);

            R result = controller.add(buildOrder(5), request); // 买5个

            assertEquals(511, result.get("code"));
            assertTrue(result.get("msg").toString().contains("库存"));
            verify(shangpinOrderService, never()).insert(any());
        }

        @Test
        @DisplayName("余额不足返回错误")
        void add_insufficient_balance() {
            yonghu.setNewMoney(5.0); // 余额5元，买2个10元的商品不够
            when(shangpinService.selectById(100)).thenReturn(shangpin);
            when(yonghuService.selectById(1)).thenReturn(yonghu);

            R result = controller.add(buildOrder(2), request);

            assertEquals(511, result.get("code"));
            assertTrue(result.get("msg").toString().contains("余额"));
        }

        @Test
        @DisplayName("购买数量为零返回错误")
        void add_zero_buy_number() {
            when(shangpinService.selectById(100)).thenReturn(shangpin);

            R result = controller.add(buildOrder(0), request);

            assertEquals(511, result.get("code"));
        }

        @Test
        @DisplayName("原子扣减库存失败时返回错误")
        void add_atomic_kucun_deduction_fails() {
            when(shangpinService.selectById(100)).thenReturn(shangpin);
            when(yonghuService.selectById(1)).thenReturn(yonghu);
            // 模拟并发场景下原子扣减失败
            when(shangpinService.update(any(ShangpinEntity.class), any(EntityWrapper.class))).thenReturn(false);

            R result = controller.add(buildOrder(1), request);

            assertEquals(511, result.get("code"));
            assertTrue(result.get("msg").toString().contains("库存不足"));
            // 扣减失败时不应插入订单
            verify(shangpinOrderService, never()).insert(any());
        }

        @Test
        @DisplayName("并发下单只有一个成功（防超卖）")
        void add_concurrent_only_one_succeeds() throws InterruptedException {
            shangpin.setShangpinKucunNumber(1); // 库存只有1
            when(shangpinService.selectById(100)).thenReturn(shangpin);
            when(yonghuService.selectById(any())).thenReturn(yonghu);
            when(shangjiaService.selectById(200)).thenReturn(shangjia);

            // 模拟原子扣减：只有第一次返回true，后续返回false
            AtomicInteger deductionCount = new AtomicInteger(0);
            when(shangpinService.update(any(ShangpinEntity.class), any(EntityWrapper.class)))
                    .thenAnswer(inv -> deductionCount.incrementAndGet() == 1);

            int threads = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threads);
            List<Future<R>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    // 每个线程创建独立的 request 和 order
                    MockHttpServletRequest req = new MockHttpServletRequest();
                    req.getSession().setAttribute("userId", 1);
                    req.getSession().setAttribute("role", "用户");
                    ShangpinOrderEntity ord = new ShangpinOrderEntity();
                    ord.setShangpinId(100);
                    ord.setBuyNumber(1);
                    ord.setShangpinOrderPaymentTypes(1);
                    R r = controller.add(ord, req);
                    doneLatch.countDown();
                    return r;
                }));
            }

            startLatch.countDown();
            doneLatch.await(10, TimeUnit.SECONDS);

            long successCount = futures.stream()
                    .map(f -> {
                        try { return f.get(5, TimeUnit.SECONDS); }
                        catch (Exception e) { return R.error(500, "exception"); }
                    })
                    .filter(r -> (int) r.get("code") == 0)
                    .count();

            assertEquals(1, successCount, "并发下单应只有一个成功");
            executor.shutdown();
        }
    }

    // ============== /refund 退款测试 ==============

    @Nested
    @DisplayName("退款 /refund")
    class RefundTests {

        private ShangpinOrderEntity buildPaidOrder() {
            ShangpinOrderEntity order = new ShangpinOrderEntity();
            order.setId(1);
            order.setShangpinId(100);
            order.setBuyNumber(2);
            order.setShangpinOrderTypes(101); //已支付
            order.setShangpinOrderPaymentTypes(1); //余额支付
            order.setShangpinOrderTruePrice(20.0);
            return order;
        }

        @Test
        @DisplayName("正常退款成功")
        void refund_success() {
            ShangpinOrderEntity order = buildPaidOrder();
            when(shangpinOrderService.selectById(1)).thenReturn(order);
            when(shangpinService.selectById(100)).thenReturn(shangpin);
            when(shangjiaService.selectById(200)).thenReturn(shangjia); // 店家余额50 >= 退款20
            when(yonghuService.selectById(1)).thenReturn(yonghu);

            R result = controller.refund(1, request);

            assertEquals(0, (int) result.get("code"));
            verify(shangpinOrderService).updateAllColumnById(argThat(o ->
                    ((ShangpinOrderEntity) o).getShangpinOrderTypes() == 102));
        }

        @Test
        @DisplayName("已退款订单不能再次退款")
        void refund_rejects_already_refunded() {
            ShangpinOrderEntity order = buildPaidOrder();
            order.setShangpinOrderTypes(102); //已退款
            when(shangpinOrderService.selectById(1)).thenReturn(order);

            R result = controller.refund(1, request);

            assertEquals(511, result.get("code"));
            assertTrue(result.get("msg").toString().contains("不允许退款"));
            verify(shangpinOrderService, never()).updateAllColumnById(any());
        }

        @Test
        @DisplayName("已出餐订单不能退款")
        void refund_rejects_delivered_order() {
            ShangpinOrderEntity order = buildPaidOrder();
            order.setShangpinOrderTypes(103); //已出餐
            when(shangpinOrderService.selectById(1)).thenReturn(order);

            R result = controller.refund(1, request);

            assertEquals(511, result.get("code"));
            assertTrue(result.get("msg").toString().contains("不允许退款"));
        }

        @Test
        @DisplayName("已取餐订单不能退款")
        void refund_rejects_received_order() {
            ShangpinOrderEntity order = buildPaidOrder();
            order.setShangpinOrderTypes(104); //已取餐
            when(shangpinOrderService.selectById(1)).thenReturn(order);

            R result = controller.refund(1, request);

            assertEquals(511, result.get("code"));
        }

        @Test
        @DisplayName("已评价订单不能退款")
        void refund_rejects_reviewed_order() {
            ShangpinOrderEntity order = buildPaidOrder();
            order.setShangpinOrderTypes(105); //已评价
            when(shangpinOrderService.selectById(1)).thenReturn(order);

            R result = controller.refund(1, request);

            assertEquals(511, result.get("code"));
        }

        @Test
        @DisplayName("店家余额不足时退款失败")
        void refund_rejects_insufficient_merchant_balance() {
            shangjia.setNewMoney(5.0); // 店家余额5 < 退款20
            ShangpinOrderEntity order = buildPaidOrder();
            when(shangpinOrderService.selectById(1)).thenReturn(order);
            when(shangpinService.selectById(100)).thenReturn(shangpin);
            when(shangjiaService.selectById(200)).thenReturn(shangjia);
            when(yonghuService.selectById(1)).thenReturn(yonghu);

            R result = controller.refund(1, request);

            assertEquals(511, result.get("code"));
            assertTrue(result.get("msg").toString().contains("店家余额不足"));
            // 不应有任何更新操作
            verify(shangpinOrderService, never()).updateAllColumnById(any());
        }

        @Test
        @DisplayName("积分支付退款被拒绝")
        void refund_rejects_points_payment() {
            ShangpinOrderEntity order = buildPaidOrder();
            order.setShangpinOrderPaymentTypes(2); //积分支付
            when(shangpinOrderService.selectById(1)).thenReturn(order);
            when(shangpinService.selectById(100)).thenReturn(shangpin);
            when(shangjiaService.selectById(200)).thenReturn(shangjia);
            when(yonghuService.selectById(1)).thenReturn(yonghu);

            R result = controller.refund(1, request);

            assertEquals(511, result.get("code"));
            assertTrue(result.get("msg").toString().contains("积分"));
        }
    }

    // ============== 状态跳转测试 ==============

    @Nested
    @DisplayName("状态转移校验")
    class StatusTransitionTests {

        private ShangpinOrderEntity buildOrder(int status) {
            ShangpinOrderEntity order = new ShangpinOrderEntity();
            order.setId(1);
            order.setShangpinId(100);
            order.setBuyNumber(1);
            order.setShangpinOrderTypes(status);
            order.setShangpinOrderPaymentTypes(1);
            return order;
        }

        // --- 出餐 deliver ---

        @Test
        @DisplayName("deliver: 已支付→已出餐 成功")
        void deliver_from_paid_success() {
            when(shangpinOrderService.selectById(1)).thenReturn(buildOrder(101));

            R result = controller.deliver(1, request);

            assertEquals(0, (int) result.get("code"));
            verify(shangpinOrderService).updateById(argThat(o ->
                    ((ShangpinOrderEntity) o).getShangpinOrderTypes() == 103));
        }

        @Test
        @DisplayName("deliver: 已退款→出餐 被拒绝")
        void deliver_from_refunded_rejected() {
            when(shangpinOrderService.selectById(1)).thenReturn(buildOrder(102));

            R result = controller.deliver(1, request);

            assertEquals(511, result.get("code"));
            verify(shangpinOrderService, never()).updateById(any());
        }

        @Test
        @DisplayName("deliver: 已出餐→出餐 被拒绝")
        void deliver_from_delivered_rejected() {
            when(shangpinOrderService.selectById(1)).thenReturn(buildOrder(103));

            R result = controller.deliver(1, request);

            assertEquals(511, result.get("code"));
        }

        // --- 取餐 receiving ---

        @Test
        @DisplayName("receiving: 已出餐→已取餐 成功")
        void receiving_from_delivered_success() {
            when(shangpinOrderService.selectById(1)).thenReturn(buildOrder(103));

            R result = controller.receiving(1, request);

            assertEquals(0, (int) result.get("code"));
            verify(shangpinOrderService).updateById(argThat(o ->
                    ((ShangpinOrderEntity) o).getShangpinOrderTypes() == 104));
        }

        @Test
        @DisplayName("receiving: 已支付→取餐 被拒绝（跳过出餐）")
        void receiving_from_paid_rejected() {
            when(shangpinOrderService.selectById(1)).thenReturn(buildOrder(101));

            R result = controller.receiving(1, request);

            assertEquals(511, result.get("code"));
        }

        @Test
        @DisplayName("receiving: 已退款→取餐 被拒绝")
        void receiving_from_refunded_rejected() {
            when(shangpinOrderService.selectById(1)).thenReturn(buildOrder(102));

            R result = controller.receiving(1, request);

            assertEquals(511, result.get("code"));
        }

        // --- 评价 commentback ---

        @Test
        @DisplayName("commentback: 已取餐→已评价 成功")
        void commentback_from_received_success() {
            when(shangpinOrderService.selectById(1)).thenReturn(buildOrder(104));

            R result = controller.commentback(1, "好吃", 5, request);

            assertEquals(0, (int) result.get("code"));
            verify(shangpinOrderService).updateById(argThat(o ->
                    ((ShangpinOrderEntity) o).getShangpinOrderTypes() == 105));
        }

        @Test
        @DisplayName("commentback: 已支付→评价 被拒绝（跳过出餐取餐）")
        void commentback_from_paid_rejected() {
            when(shangpinOrderService.selectById(1)).thenReturn(buildOrder(101));

            R result = controller.commentback(1, "好吃", 5, request);

            assertEquals(511, result.get("code"));
        }

        @Test
        @DisplayName("commentback: 已退款→评价 被拒绝")
        void commentback_from_refunded_rejected() {
            when(shangpinOrderService.selectById(1)).thenReturn(buildOrder(102));

            R result = controller.commentback(1, "好吃", 5, request);

            assertEquals(511, result.get("code"));
        }

        @Test
        @DisplayName("commentback: 已出餐→评价 被拒绝（还没取餐）")
        void commentback_from_delivered_rejected() {
            when(shangpinOrderService.selectById(1)).thenReturn(buildOrder(103));

            R result = controller.commentback(1, "好吃", 5, request);

            assertEquals(511, result.get("code"));
        }

        @Test
        @DisplayName("commentback: 已评价→再次评价 被拒绝")
        void commentback_from_reviewed_rejected() {
            when(shangpinOrderService.selectById(1)).thenReturn(buildOrder(105));

            R result = controller.commentback(1, "再评一次", 5, request);

            assertEquals(511, result.get("code"));
        }
    }

    // ============== /order 多品下单测试 ==============

    @Nested
    @DisplayName("多品下单 /order")
    class OrderTests {

        @Test
        @DisplayName("积分支付被拒绝")
        void order_rejects_points_payment() {
            Map<String, Object> params = new HashMap<>();
            params.put("shangpinOrderPaymentTypes", "2");
            params.put("shangpins", "[]");

            R result = controller.add(params, request);

            assertEquals(511, result.get("code"));
            assertTrue(result.get("msg").toString().contains("积分"));
        }

        @Test
        @DisplayName("正常多品下单成功")
        void order_success() {
            when(shangpinService.selectById(100)).thenReturn(shangpin);
            when(shangjiaService.selectById(200)).thenReturn(shangjia);
            when(yonghuService.selectById(1)).thenReturn(yonghu);
            when(shangpinService.update(any(ShangpinEntity.class), any(EntityWrapper.class))).thenReturn(true);

            Map<String, Object> params = new HashMap<>();
            params.put("shangpinOrderPaymentTypes", "1");
            params.put("shangpins", "[{\"shangpinId\":100,\"buyNumber\":2}]");

            R result = controller.add(params, request);

            assertEquals(0, (int) result.get("code"));
            verify(shangpinOrderService).insertBatch(anyList());
            verify(shangpinService).update(any(ShangpinEntity.class), any(EntityWrapper.class));
        }

        @Test
        @DisplayName("多品下单库存不足返回错误")
        void order_insufficient_stock() {
            shangpin.setShangpinKucunNumber(1);
            when(shangpinService.selectById(100)).thenReturn(shangpin);
            when(yonghuService.selectById(any())).thenReturn(yonghu);

            Map<String, Object> params = new HashMap<>();
            params.put("shangpinOrderPaymentTypes", "1");
            params.put("shangpins", "[{\"shangpinId\":100,\"buyNumber\":5}]");

            R result = controller.add(params, request);

            // 返回错误（code != 0 表示失败）
            assertNotEquals(0, result.get("code"));
        }
    }
}
