package com.fc.v2.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TZwStopBillMapper;
import com.fc.v2.model.auto.TZwStopBill;
import com.fc.v2.service.ITZwStopBillService;
import com.fc.v2.service.zw.GateView;
import com.fc.v2.service.zw.StopBillView;

/**
 * 停供/限供申请签核单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * <p>本骨架只把关口次序和报批情形这两个最基本的面搭起来，会签如何点算、
 * 落印台账怎么记、打回之后如何作废重攒，都还没做——题面要的就是这几件事。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TZwStopBillServiceImpl implements ITZwStopBillService {

    /** 当前所在道的取值范围：0..3 */
    private static final int MAX_NODE = 3;

    /** 报批情形：0 在核 / 1 已核讫 / 2 已终止 */
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_STOPPED = 2;

    /** 停水事由 */
    private static final String REASON_DEPLETED = "DEPLETED";
    private static final String REASON_OVER_LIMIT = "OVER_LIMIT";
    private static final String REASON_REPAIR = "REPAIR";

    @javax.annotation.Resource
    private TZwStopBillMapper zwStopBillMapper;

    @Override
    public TZwStopBill selectTZwStopBillById(Long id) {
        return this.zwStopBillMapper.selectById(id);
    }

    @Override
    public List<TZwStopBill> selectTZwStopBillList(Wrapper<TZwStopBill> queryWrapper) {
        return this.zwStopBillMapper.selectList(queryWrapper);
    }

    @Override
    public TZwStopBill submit(String siteNo, String reason, String altPlan, String applicant, String opinion) {
        if (siteNo == null || siteNo.trim().isEmpty()) {
            return null;
        }
        if (!REASON_DEPLETED.equals(reason) && !REASON_OVER_LIMIT.equals(reason)
                && !REASON_REPAIR.equals(reason)) {
            return null;
        }
        if (altPlan == null || altPlan.trim().isEmpty()) {
            return null;
        }
        int year = yearOf(new Date());
        TZwStopBill r = new TZwStopBill();
        r.setBillNo(siteNo.trim() + "-" + year + "-01");
        r.setSiteNo(siteNo.trim());
        r.setBillYear(Integer.valueOf(year));
        r.setYearSeq(Integer.valueOf(1));
        r.setReason(reason);
        r.setAltPlan(altPlan.trim());
        r.setNodeNo(Integer.valueOf(0));
        r.setRoundNo(Integer.valueOf(1));
        r.setStatus(Integer.valueOf(STATUS_RUNNING));
        if (this.zwStopBillMapper.insert(r) != 1) {
            return null;
        }
        // TODO 提单即落业务科自己的名，单子进第二道；公告文案也要按事由从名录带出
        return this.zwStopBillMapper.selectById(r.getId());
    }

    /** 老签法（入参与返回照旧）：当前关口的同意签批 */
    @Override
    public TZwStopBill approve(Long id, String approver, String comment) {
        TZwStopBill r = findRunning(id, approver);
        if (r == null) {
            return null;
        }
        // TODO 本道口够不够人还没点算：现在来一个人就往下推一道
        advance(r);
        this.zwStopBillMapper.updateById(r);
        return this.zwStopBillMapper.selectById(id);
    }

    /** 另一种签法（同名重载）：调度评估口两名评估人各按落名口签，各评各的、互不替签 */
    @Override
    public TZwStopBill approve(Long id, String approver, String signRole, String comment) {
        // TODO 还没实现：谁占过哪个落名口要顺签核台账点算，同一个人不能把两个口都占了
        return approve(id, approver, comment);
    }

    @Override
    public TZwStopBill reject(Long id, String approver, String comment) {
        return reject(id, approver, null, comment);
    }

    @Override
    public TZwStopBill reject(Long id, String approver, String signRole, String comment) {
        TZwStopBill r = findRunning(id, approver);
        if (r == null) {
            return null;
        }
        // 写明不同意：单到此为止
        r.setStatus(Integer.valueOf(STATUS_STOPPED));
        this.zwStopBillMapper.updateById(r);
        return this.zwStopBillMapper.selectById(id);
    }

    @Override
    public TZwStopBill withdraw(Long id, String applicant, String comment) {
        TZwStopBill r = findRunning(id, applicant);
        if (r == null) {
            return null;
        }
        // TODO 撤回也要在台账落一笔，之后谁也不往它身上追记
        r.setStatus(Integer.valueOf(STATUS_STOPPED));
        this.zwStopBillMapper.updateById(r);
        return this.zwStopBillMapper.selectById(id);
    }

    @Override
    public TZwStopBill rollback(Long id, String comment) {
        TZwStopBill r = findRunning(id, null);
        if (r == null) {
            return null;
        }
        // TODO 打回要旧轮整轮作废：轮次加一、退回第二道，作废的与补签的不摆在一起
        int node = nodeOf(r);
        r.setNodeNo(Integer.valueOf(node > 1 ? node - 1 : 1));
        this.zwStopBillMapper.updateById(r);
        return this.zwStopBillMapper.selectById(id);
    }

    @Override
    public TZwStopBill sendBack(Long id, String approver, String comment) {
        // TODO 主管认为理由不符把单打回：只有主管批准口能打回
        return null;
    }

    @Override
    public StopBillView buildView(Long id) {
        TZwStopBill bill = this.zwStopBillMapper.selectById(id);
        if (bill == null) {
            return null;
        }
        // TODO 各关过没过、每口应到/已到几枚名，都要顺着当前轮签核台账一次点算出来
        List<GateView> gates = new ArrayList<GateView>();
        return new StopBillView(bill, gates, new ArrayList<com.fc.v2.model.auto.TZwStopSign>(),
                new ArrayList<com.fc.v2.model.auto.TZwStopSign>());
    }

    /** 推进：未到主管批准口则进下一道口；主管批准口之后置为已核讫且各栏锁死 */
    private void advance(TZwStopBill r) {
        int node = nodeOf(r);
        if (node >= MAX_NODE) {
            r.setStatus(Integer.valueOf(STATUS_PASS));
            return;
        }
        r.setNodeNo(Integer.valueOf(node + 1));
    }

    private TZwStopBill findRunning(Long id, String approver) {
        if (id == null) {
            return null;
        }
        if (approver != null && approver.trim().isEmpty()) {
            return null;
        }
        TZwStopBill r = this.zwStopBillMapper.selectById(id);
        if (r == null || r.getStatus() == null
                || r.getStatus().intValue() != STATUS_RUNNING) {
            return null;
        }
        int node = nodeOf(r);
        if (node < 0 || node > MAX_NODE) {
            return null;
        }
        return r;
    }

    private int nodeOf(TZwStopBill r) {
        return r.getNodeNo() == null ? 0 : r.getNodeNo().intValue();
    }

    private int yearOf(Date d) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTime(d);
        return c.get(java.util.Calendar.YEAR);
    }
}