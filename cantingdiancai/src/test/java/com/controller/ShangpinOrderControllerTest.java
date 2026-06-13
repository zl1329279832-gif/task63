package com.controller;

import com.dao.ShangpinDao;
import com.entity.ShangpinEntity;
import com.entity.ShangpinOrderEntity;
import com.entity.ShangjiaEntity;
import com.entity.YonghuEntity;
import com.service.ShangpinOrderService;
import com.service.ShangpinService;
import com.service.ShangjiaService;
import com.service.YonghuService;
import com.utils.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ShangpinOrderController 并发下单与状态跳转测试
 *
 * 需要连接 MySQL cantingdiancai 数据库运行
 */
@SpringBootTest
public class ShangpinOrderControllerTest {

    @Autowired
    private ShangpinOrderController shangpinOrderController;

    @Autowired
    private ShangpinService shangpinService;

    @Autowired
    private ShangpinOrderService shangpinOrderService;

    @Autowired
    private YonghuService yonghuService;

    @Autowired
    private ShangjiaService shangjiaService;

    @Autowired
    private ShangpinDao shangpinDao;

    private MockHttpServletRequest request;

    // 测试用固定ID（使用较大ID避免与已有数据冲突）
    private static final int TEST_SHANGPIN_ID = 99901;
    private static final int TEST_YONGHU_ID = 99901;
    private static final int TEST_SHANGJIA_ID = 99901;
    private static final int TEST_ORDER_ID = 99901;

    @BeforeEach
    public void setUp() {
        request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", TEST_YONGHU_ID);
        session.setAttribute("role", "用户");
        request.setSession(session);
    }

    /**
     * 准备测试商品（库存=指定值）
     */
    private void prepareShangpin(int kucun) {
        ShangpinEntity sp = shangpinService.selectById(TEST_SHANGPIN_ID);
        if (sp == null) {
            sp = new ShangpinEntity();
            sp.setId(TEST_SHANGPIN_ID);
            sp.setShangjiaId(TEST_SHANGJIA_ID);
            sp.setShangpinName("测试菜品");
            sp.setShangpinUuidNumber("TEST_SP_001");
            sp.setShangpinKucunNumber(kucun);
            sp.setShangpinNewMoney(10.0);
            sp.setShangpinOldMoney(15.0);
            sp.setShangxiaTypes(1);
            sp.setShangpinDelete(0);
            sp.setShangpinClicknum(0);
            sp.setZanNumber(0);
            sp.setCaiNumber(0);
            sp.setInsertTime(new Date());
            sp.setCreateTime(new Date());
            shangpinService.insert(sp);
        } else {
            sp.setShangpinKucunNumber(kucun);
            sp.setShangpinNewMoney(10.0);
            shangpinService.updateById(sp);
        }
    }

    /**
     * 准备测试用户（余额=指定值）
     */
    private void prepareYonghu(double balance) {
        YonghuEntity user = yonghuService.selectById(TEST_YONGHU_ID);
        if (user == null) {
            user = new YonghuEntity();
            user.setId(TEST_YONGHU_ID);
            user.setUsername("testuser99901");
            user.setPassword("123456");
            user.setYonghuName("测试用户");
            user.setNewMoney(balance);
            user.setCreateTime(new Date());
            yonghuService.insert(user);
        } else {
            user.setNewMoney(balance);
            yonghuService.updateById(user);
        }
    }

    /**
     * 准备测试店家（余额=指定值）
     */
    private void prepareShangjia(double balance) {
        ShangjiaEntity sj = shangjiaService.selectById(TEST_SHANGJIA_ID);
        if (sj == null) {
            sj = new ShangjiaEntity();
            sj.setId(TEST_SHANGJIA_ID);
            sj.setUsername("testshop99901");
            sj.setPassword("123456");
            sj.setShangjiaName("测试店家");
            sj.setNewMoney(balance);
            sj.setShangjiaDelete(0);
            sj.setCreateTime(new Date());
            shangjiaService.insert(sj);
        } else {
            sj.setNewMoney(balance);
            shangjiaService.updateById(sj);
        }
    }

    /**
     * 准备测试订单
     */
    private void prepareOrder(int orderId, int status, double truePrice) {
        ShangpinOrderEntity order = shangpinOrderService.selectById(orderId);
        if (order == null) {
            order = new ShangpinOrderEntity();
            order.setId(orderId);
            order.setShangpinOrderUuidNumber("TEST_ORDER_" + orderId);
            order.setShangpinId(TEST_SHANGPIN_ID);
            order.setYonghuId(TEST_YONGHU_ID);
            order.setBuyNumber(1);
            order.setShangpinOrderTruePrice(truePrice);
            order.setShangpinOrderTypes(status);
            order.setShangpinOrderPaymentTypes(1);
            order.setInsertTime(new Date());
            order.setCreateTime(new Date());
            shangpinOrderService.insert(order);
        } else {
            order.setShangpinOrderTypes(status);
            order.setShangpinOrderTruePrice(truePrice);
            order.setShangpinId(TEST_SHANGPIN_ID);
            order.setBuyNumber(1);
            shangpinOrderService.updateAllColumnById(order);
        }
    }

    // ==================== 并发下单测试 ====================

    /**
     * 并发扣减库存测试：库存=1，10个线程同时扣减，只允许1个成功
     * 验证 deductKucun 的原子性，确保库存不会变成负数
     */
    @Test
    @Transactional
    @Rollback
    public void testConcurrentDeductKucun() throws InterruptedException {
        prepareShangpin(1); // 库存=1

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 所有线程同时开始
                    int result = shangpinDao.deductKucun(TEST_SHANGPIN_ID, 1);
                    if (result == 1) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 放闸
        endLatch.await();       // 等待所有线程完成
        executor.shutdown();

        assertEquals(1, successCount.get(), "只能有1个线程扣减成功");
        assertEquals(threadCount - 1, failCount.get(), "其余线程应全部失败");

        // 验证库存不为负数
        ShangpinEntity sp = shangpinService.selectById(TEST_SHANGPIN_ID);
        assertTrue(sp.getShangpinKucunNumber() >= 0, "库存不能为负数");
        assertEquals(0, sp.getShangpinKucunNumber(), "库存应恰好为0");
    }

    /**
     * 并发扣减库存测试：库存=5，10个线程各扣1，只能成功5个
     */
    @Test
    @Transactional
    @Rollback
    public void testConcurrentDeductKucunPartial() throws InterruptedException {
        prepareShangpin(5); // 库存=5

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int result = shangpinDao.deductKucun(TEST_SHANGPIN_ID, 1);
                    if (result == 1) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        executor.shutdown();

        assertEquals(5, successCount.get(), "库存为5时只能成功5个");

        ShangpinEntity sp = shangpinService.selectById(TEST_SHANGPIN_ID);
        assertEquals(0, sp.getShangpinKucunNumber(), "最终库存应为0");
    }

    // ==================== 订单状态跳转测试 ====================

    /**
     * 已退款(102)订单不能再退款
     */
    @Test
    @Transactional
    @Rollback
    public void testRefundAlreadyRefundedOrder() {
        prepareShangpin(10);
        prepareShangjia(100.0);
        prepareYonghu(100.0);
        prepareOrder(TEST_ORDER_ID, 102, 10.0); // 已退款

        R result = shangpinOrderController.refund(TEST_ORDER_ID, request);
        assertNotEquals(0, result.get("code"), "已退款订单不应允许再次退款");
        assertTrue(result.get("msg").toString().contains("已退款"), "应返回已退款提示");
    }

    /**
     * 已出餐(103)订单不能退款
     */
    @Test
    @Transactional
    @Rollback
    public void testRefundDeliveredOrder() {
        prepareShangpin(10);
        prepareShangjia(100.0);
        prepareYonghu(100.0);
        prepareOrder(TEST_ORDER_ID, 103, 10.0); // 已出餐

        R result = shangpinOrderController.refund(TEST_ORDER_ID, request);
        assertNotEquals(0, result.get("code"), "已出餐订单不应允许退款");
        assertTrue(result.get("msg").toString().contains("已出餐"), "应返回已出餐提示");
    }

    /**
     * 已退款(102)订单不能出餐
     */
    @Test
    @Transactional
    @Rollback
    public void testDeliverRefundedOrder() {
        prepareShangpin(10);
        prepareShangjia(100.0);
        prepareOrder(TEST_ORDER_ID, 102, 10.0); // 已退款

        R result = shangpinOrderController.deliver(TEST_ORDER_ID, request);
        assertNotEquals(0, result.get("code"), "已退款订单不应允许出餐");
        assertTrue(result.get("msg").toString().contains("退款"), "应返回退款相关提示");
    }

    /**
     * 已支付(101)订单不能直接评价，必须先出餐再取餐
     */
    @Test
    @Transactional
    @Rollback
    public void testCommentbackOnPaidOrder() {
        prepareShangpin(10);
        prepareOrder(TEST_ORDER_ID, 101, 10.0); // 已支付

        R result = shangpinOrderController.commentback(TEST_ORDER_ID, "好吃", 5, request);
        assertNotEquals(0, result.get("code"), "已支付订单不应允许评价");
    }

    /**
     * 已出餐(103)订单不能直接评价，需要先取餐
     */
    @Test
    @Transactional
    @Rollback
    public void testCommentbackOnDeliveredOrder() {
        prepareShangpin(10);
        prepareOrder(TEST_ORDER_ID, 103, 10.0); // 已出餐

        R result = shangpinOrderController.commentback(TEST_ORDER_ID, "好吃", 5, request);
        assertNotEquals(0, result.get("code"), "已出餐但未取餐订单不应允许评价");
    }

    /**
     * 已取餐(104)订单可以评价 — 正常流程
     */
    @Test
    @Transactional
    @Rollback
    public void testCommentbackOnReceivedOrder() {
        prepareShangpin(10);
        prepareOrder(TEST_ORDER_ID, 104, 10.0); // 已取餐

        R result = shangpinOrderController.commentback(TEST_ORDER_ID, "好吃", 5, request);
        assertEquals(0, result.get("code"), "已取餐订单应允许评价");

        // 验证状态已变为已评价(105)
        ShangpinOrderEntity order = shangpinOrderService.selectById(TEST_ORDER_ID);
        assertEquals(105, order.getShangpinOrderTypes().intValue(), "评价后状态应为105");
    }

    /**
     * 已支付(101)订单不能直接取餐，需要先出餐
     */
    @Test
    @Transactional
    @Rollback
    public void testReceivingOnPaidOrder() {
        prepareShangpin(10);
        prepareOrder(TEST_ORDER_ID, 101, 10.0);

        R result = shangpinOrderController.receiving(TEST_ORDER_ID, request);
        assertNotEquals(0, result.get("code"), "已支付但未出餐订单不应允许取餐");
    }

    /**
     * 已出餐(103)订单可以取餐 — 正常流程
     */
    @Test
    @Transactional
    @Rollback
    public void testReceivingOnDeliveredOrder() {
        prepareShangpin(10);
        prepareOrder(TEST_ORDER_ID, 103, 10.0);

        R result = shangpinOrderController.receiving(TEST_ORDER_ID, request);
        assertEquals(0, result.get("code"), "已出餐订单应允许取餐");

        ShangpinOrderEntity order = shangpinOrderService.selectById(TEST_ORDER_ID);
        assertEquals(104, order.getShangpinOrderTypes().intValue(), "取餐后状态应为104");
    }

    /**
     * 正常退款流程：已支付(101) → 退款(102)，验证余额和库存回滚
     */
    @Test
    @Transactional
    @Rollback
    public void testRefundNormalFlow() {
        prepareShangpin(9);       // 库存9（下单后减1变8，退款后应恢复为9+1=10）
        prepareShangjia(100.0);   // 店家余额100
        prepareYonghu(90.0);      // 用户余额90
        prepareOrder(TEST_ORDER_ID, 101, 10.0); // 已支付订单，实付10元

        R result = shangpinOrderController.refund(TEST_ORDER_ID, request);
        assertEquals(0, result.get("code"), "正常退款应成功");

        // 验证订单状态
        ShangpinOrderEntity order = shangpinOrderService.selectById(TEST_ORDER_ID);
        assertEquals(102, order.getShangpinOrderTypes().intValue(), "退款后状态应为102");

        // 验证库存恢复
        ShangpinEntity sp = shangpinService.selectById(TEST_SHANGPIN_ID);
        assertEquals(10, sp.getShangpinKucunNumber().intValue(), "退款后库存应恢复");

        // 验证用户余额增加
        YonghuEntity user = yonghuService.selectById(TEST_YONGHU_ID);
        assertEquals(100.0, user.getNewMoney(), 0.01, "退款后用户余额应增加");

        // 验证店家余额减少
        ShangjiaEntity sj = shangjiaService.selectById(TEST_SHANGJIA_ID);
        assertEquals(90.0, sj.getNewMoney(), 0.01, "退款后店家余额应减少");
    }

    /**
     * 退款时店家余额不足
     */
    @Test
    @Transactional
    @Rollback
    public void testRefundInsufficientShopBalance() {
        prepareShangpin(10);
        prepareShangjia(5.0);     // 店家余额只有5元
        prepareYonghu(100.0);
        prepareOrder(TEST_ORDER_ID, 101, 10.0); // 实付10元

        R result = shangpinOrderController.refund(TEST_ORDER_ID, request);
        assertNotEquals(0, result.get("code"), "店家余额不足时退款应失败");
        assertTrue(result.get("msg").toString().contains("店家余额不足"), "应提示店家余额不足");
    }

    /**
     * 积分支付应被明确拒绝
     */
    @Test
    @Transactional
    @Rollback
    public void testRejectPointsPayment() {
        prepareShangpin(10);
        prepareShangjia(100.0);
        prepareYonghu(100.0);

        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("shangpinOrderPaymentTypes", "2"); // 积分支付
        params.put("shangpins", "[{\"shangpinId\":" + TEST_SHANGPIN_ID + ",\"buyNumber\":1}]");

        R result = shangpinOrderController.add(params, request);
        assertNotEquals(0, result.get("code"), "积分支付应被拒绝");
        assertTrue(result.get("msg").toString().contains("积分"), "应提示不支持积分支付");
    }
}
