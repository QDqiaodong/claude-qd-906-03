package com.hog.farm.controller;

import com.hog.farm.common.ApiResponse;
import com.hog.farm.common.Dict;
import com.hog.farm.dto.BatchStatusStatDTO;
import com.hog.farm.dto.BreedStatDTO;
import com.hog.farm.dto.HouseTypeStatDTO;
import com.hog.farm.dto.Views.OverviewVO;
import com.hog.farm.service.StatsService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 统计：概览数字 + 三张分布图 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService service;

    public StatsController(StatsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<OverviewVO> overview() {
        return ApiResponse.success(service.overview());
    }

    @GetMapping("/house-type")
    public ApiResponse<List<HouseTypeStatDTO>> houseType() {
        return ApiResponse.success(service.houseType());
    }

    @GetMapping("/breed")
    public ApiResponse<List<BreedStatDTO>> breed() {
        return ApiResponse.success(service.breed());
    }

    @GetMapping("/batch-status")
    public ApiResponse<List<BatchStatusStatDTO>> batchStatus() {
        return ApiResponse.success(service.batchStatus());
    }

    /** 字典：前端下拉与图例统一从这里取 */
    @GetMapping("/dict")
    public ApiResponse<Map<String, Object>> dict() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("houseTypes", options(List.of("GESTATION", "FARROWING", "NURSERY", "FINISHING", "ISOLATION"),
                Dict::houseTypeName));
        data.put("houseStatuses", options(List.of("ACTIVE", "MAINTAIN", "DISABLED"), Dict::houseStatusName));
        data.put("breeds", options(List.of("YORKSHIRE", "LANDRACE", "DUROC", "PIETRAIN", "BERKSHIRE"),
                Dict::breedName));
        data.put("pigStatuses", options(Dict.PIG_FLOW, Dict::pigStatusName));
        data.put("batchStatuses", options(Dict.BATCH_FLOW, Dict::batchStatusName));
        data.put("traceTypes", options(List.of("IMMUNE", "TRANSFER", "FEEDING", "SALE", "DEATH", "CHECK"),
                Dict::traceTypeName));
        data.put("targetTypes", options(List.of("PIG", "BATCH", "HOUSE", "FEED_BATCH"), Dict::targetTypeName));
        data.put("feedTypes", options(List.of("CREEP", "NURSERY", "FINISHER", "SOW", "BOAR"), Dict::feedTypeName));
        data.put("transferReasons", options(List.of("GROWTH", "WEAN", "ISOLATE", "DISEASE", "DENSITY", "SELL"),
                Dict::transferReasonName));
        data.put("vaccines", Dict.VACCINES);
        return ApiResponse.success(data);
    }

    private List<Map<String, String>> options(List<String> codes, Function<String, String> namer) {
        List<Map<String, String>> result = new ArrayList<>();
        for (String code : codes) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("value", code);
            item.put("label", namer.apply(code));
            result.add(item);
        }
        return result;
    }
}
