package com.gyejoldanji.domain.analytics.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gyejoldanji.domain.analytics.config.AnalyticsSheetSyncProperties;
import com.gyejoldanji.domain.analytics.entity.AnalyticsEvent;
import com.gyejoldanji.domain.analytics.repository.AnalyticsEventRepository;
import com.gyejoldanji.global.infrastructure.google.GoogleSheetsGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DB 원본을 시트에 내보내고 성공한 배치만 짧은 별도 트랜잭션에서 표시한다. API 요청에서는 호출하지 않는다.
 * shortcut: 단일 인스턴스 전제다. 여러 인스턴스에서 실행하게 되면 시트 읽기부터 DB 표시까지 분산 잠금이 필요하다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = {"app.google-sheets.enabled", "app.analytics.google-sheets.enabled"}, havingValue = "true")
public class AnalyticsSheetSyncService {

    private final AnalyticsEventRepository repository;
    private final GoogleSheetsGateway gateway;
    private final AnalyticsSheetRowMapper rowMapper;
    private final AnalyticsSheetSyncProperties properties;
    private final Clock clock;
    private final TransactionTemplate markingTransaction;

    public AnalyticsSheetSyncService(AnalyticsEventRepository repository, GoogleSheetsGateway gateway,
                                     AnalyticsSheetRowMapper rowMapper, AnalyticsSheetSyncProperties properties,
                                     Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.gateway = gateway;
        this.rowMapper = rowMapper;
        this.properties = properties;
        this.clock = clock;
        this.markingTransaction = new TransactionTemplate(transactionManager);
        this.markingTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 한 인스턴스 안에서도 두 회차가 같은 셀 범위를 동시에 고르지 않도록 직렬화한다. */
    public synchronized void synchronize() {
        int written = 0;
        int existing = 0;
        try {
            properties.validateForSync();
            int batchSize = properties.batchSizeValue();
            int maxBatches = properties.maxBatchesValue();
            String spreadsheetId = properties.getSpreadsheetId();
            String sheetName = properties.getSheetName();
            String rangePrefix = "'" + sheetName.replace("'", "''") + "'!";
            List<List<Object>> idsColumn = gateway.readValues(spreadsheetId, rangePrefix + "A:A");
            // 중간 빈 행도 위치를 차지하므로 event_id 집합 크기로 다음 행을 계산하지 않는다.
            int lastRow = idsColumn.size();
            Set<String> seen = new HashSet<>();
            for (List<Object> row : idsColumn) {
                if (!row.isEmpty() && row.get(0) != null) {
                    seen.add(row.get(0).toString());
                }
            }
            if (lastRow == 0) {
                gateway.ensureRowCapacity(spreadsheetId, sheetName, 1);
                gateway.batchUpdateValues(spreadsheetId,
                        Map.of(rangePrefix + "A1:W1", List.of(new ArrayList<>(AnalyticsSheetRowMapper.HEADERS))));
                lastRow = 1;
            }

            for (int batch = 0; batch < maxBatches; batch++) {
                List<AnalyticsEvent> pending = repository.findPendingForSheet(batchSize);
                if (pending.isEmpty()) {
                    break;
                }
                List<List<Object>> rows = new ArrayList<>();
                for (AnalyticsEvent event : pending) {
                    if (seen.add(event.getEventId())) {
                        rows.add(rowMapper.toRow(event, properties.getUserKeySecret()));
                    }
                }
                if (!rows.isEmpty()) {
                    int endRow = Math.addExact(lastRow, rows.size());
                    gateway.ensureRowCapacity(spreadsheetId, sheetName, endRow);
                    gateway.batchUpdateValues(spreadsheetId,
                            Map.of(rangePrefix + "A" + (lastRow + 1) + ":W" + endRow, rows));
                    written += rows.size();
                    lastRow = endRow;
                }
                List<Long> ids = pending.stream().map(AnalyticsEvent::getId).toList();
                markingTransaction.executeWithoutResult(status -> repository.markSheetSynced(ids,
                        clock.instant().truncatedTo(ChronoUnit.MICROS)));
                existing += pending.size() - rows.size();
            }
            log.info("행동 이벤트 시트 동기화 완료: written={}, existing={}, pending={}",
                    written, existing, repository.existsBySheetSyncedAtIsNull());
        } catch (RuntimeException exception) {
            log.warn("행동 이벤트 시트 동기화 건너뜀 또는 실패: type={}, written={}, existing={}",
                    exception.getClass().getName(), written, existing);
        }
    }
}
