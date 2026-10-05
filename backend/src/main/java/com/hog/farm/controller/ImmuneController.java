package com.hog.farm.controller;

import com.hog.farm.common.ApiResponse;
import com.hog.farm.domain.ImmunePlan;
import com.hog.farm.dto.ImmuneConfirmRequest;
import com.hog.farm.dto.ImmunePlanRequest;
import com.hog.farm.dto.Views.ImmuneBoardVO;
import com.hog.farm.dto.Views.ImmuneConfirmResultVO;
import com.hog.farm.dto.Views.ImmuneGenerateVO;
import com.hog.farm.dto.Views.ImmunePlanVO;
import com.hog.farm.dto.Views.ImmuneTaskVO;
import com.hog.farm.service.ImmuneService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 免疫计划与接种任务（生成时名单快照口径） */
@RestController
@RequestMapping("/api/immune")
public class ImmuneController {

    private final ImmuneService service;

    public ImmuneController(ImmuneService service) {
        this.service = service;
    }

    // ---------------- 计划规则 ----------------

    @GetMapping("/plan/list")
    public ApiResponse<List<ImmunePlanVO>> plans() {
        return ApiResponse.success(service.listPlans());
    }

    @PostMapping("/plan")
    public ApiResponse<ImmunePlan> createPlan(@RequestBody ImmunePlanRequest request) {
        return ApiResponse.success("免疫计划已创建", service.createPlan(request));
    }

    /** 停用 / 启用计划（停用时未完成任务进入可追溯的终止状态） */
    @PostMapping("/plan/{id}/status")
    public ApiResponse<ImmunePlan> changePlanStatus(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success("计划状态已更新", service.changePlanStatus(id, status));
    }

    // ---------------- 任务生成 ----------------

    /** 生成预检：逐头解释被纳入 / 暂缓 / 不再适用的原因，不落库 */
    @GetMapping("/plan/{id}/preview")
    public ApiResponse<ImmuneGenerateVO> preview(@PathVariable Long id) {
        return ApiResponse.success(service.preview(id));
    }

    /** 生成到期接种任务（生成时名单快照；已存在的剂次跳过不重复） */
    @PostMapping("/plan/{id}/generate")
    public ApiResponse<ImmuneGenerateVO> generate(@PathVariable Long id) {
        return ApiResponse.success("任务生成完成", service.generate(id));
    }

    // ---------------- 看板与确认 ----------------

    /** 看板：按 待接种 / 临期 / 漏种 / 已补种 / 已完成 / 已终止 分组 */
    @GetMapping("/board")
    public ApiResponse<ImmuneBoardVO> board() {
        return ApiResponse.success(service.board());
    }

    /** 猪只详情追溯：该猪只的全部接种任务 */
    @GetMapping("/task/list")
    public ApiResponse<List<ImmuneTaskVO>> tasks(@RequestParam(required = false) Long pigId) {
        return ApiResponse.success(service.listTasks(pigId));
    }

    /** 批量确认接种：一次多头；局部失败互不影响；同一 requestNo 重试幂等 */
    @PostMapping("/task/confirm")
    public ApiResponse<ImmuneConfirmResultVO> confirm(@RequestBody ImmuneConfirmRequest request) {
        return ApiResponse.success("确认完成", service.confirm(request));
    }
}
