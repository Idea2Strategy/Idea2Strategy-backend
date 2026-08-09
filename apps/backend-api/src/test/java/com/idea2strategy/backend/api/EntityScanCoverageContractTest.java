package com.idea2strategy.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.api.competition.CompetitionRoomConfiguration;
import com.idea2strategy.backend.api.identity.IdentityAuthConfiguration;
import com.idea2strategy.backend.api.strategy.StrategyDraftConfiguration;
import jakarta.persistence.Entity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * 모든 JPA 엔티티가 어떤 {@link EntityScan} 선언에도 걸리지 않은 채 남지 않게 한다.
 *
 * <p>Spring Boot 의 기본 엔티티 스캔은 {@code @EntityScan} 이 하나라도 선언되면 그것으로
 * 대체된다. 이 애플리케이션은 이미 여러 곳에서 선언하고 있으므로, 새 엔티티를 만들고 해당
 * 설정에 추가하지 않으면 그 엔티티는 persistence unit 에 들어가지 않는다.
 *
 * <p>그 실패는 기동 시점에 드러나지 않는다. 컨텍스트는 정상적으로 뜨고, 요청 검증도 전부
 * 통과하고, 저장하려는 순간에야 {@code "does not belong to this persistence unit"} 으로 죽는다.
 * 배포된 환경에서 방 생성이 정확히 그렇게 실패했고, 게다가 HTTP 400 으로 보고되어 요청이 잘못된
 * 것처럼 읽혔다. 저장을 시도하는 통합 시험이 없는 엔티티는 이 결함을 안고도 초록색으로 남는다.
 *
 * <p>이 시험은 데이터베이스를 필요로 하지 않으므로 Docker 가 없는 기계에서도 돌고 skip 되지
 * 않는다.
 *
 * <p><strong>설정 목록을 손으로 유지한다.</strong> 처음에는
 * {@code ClassPathScanningCandidateComponentProvider} 로 {@code @EntityScan} 이 붙은 설정을
 * 찾으려 했는데, 이 모듈의 시험 클래스패스에서 그 스캔은 <em>main 클래스를 보지 못했다</em> —
 * 찾아낸 후보가 시험 클래스 하나뿐이었고, 그래서 이미 덮여 있는 strategy·identity 엔티티까지
 * 누락으로 신고했다. 검사를 조용히 무력화하는 쪽보다 목록이 눈에 보이는 쪽이 낫다. 엔티티 쪽
 * 스캔은 정상 동작하므로 그대로 쓴다 — 새 엔티티는 자동으로 잡히고, 그것을 덮을 설정을 아래
 * 목록에 넣지 않았다면 이 시험이 실패한다.
 */
class EntityScanCoverageContractTest {
    private static final String PERSISTENCE_ROOT = "com.idea2strategy.backend.persistence";

    /**
     * 아직 어떤 애플리케이션도 배선하지 않은 JPA 어댑터의 엔티티. 지금은 아무도 저장하지
     * 않으므로 깨지지 않지만, 배선하는 순간 persistence unit 에 없어 저장에서 실패한다.
     *
     * <p>여기에 두는 것은 무시가 아니라 <em>기한이 있는 예외</em>다. 배선할 때 이 목록에서 빼고
     * 그 도메인의 설정에 {@code @EntityScan} 을 추가해야 한다. 확인한 사실 -- backend-api 와
     * backend-worker 어디에서도 {@code BotJpaCommandAdapter},
     * {@code BotSpringDataRepository}, {@code BotCurrentPerformance*} 를 참조하지 않는다.
     */
    private static final Set<String> UNWIRED_ENTITIES = Set.of(
            "com.idea2strategy.backend.persistence.botcontrol.BotJpaEntity",
            "com.idea2strategy.backend.persistence.performance.BotCurrentPerformanceJpaEntity");

    /** {@code @EntityScan} 을 선언하는 애플리케이션 설정. 새로 선언하는 곳이 생기면 추가한다. */
    private static final List<Class<?>> ENTITY_SCAN_DECLARERS = List.of(
            IdentityAuthConfiguration.class,
            StrategyDraftConfiguration.class,
            CompetitionRoomConfiguration.class);

    @Test
    @DisplayName("모든 @Entity 가 어떤 @EntityScan 범위 안에 있다")
    void everyEntityIsCoveredByAnEntityScan() {
        Set<String> scanned = scannedPackages();
        assertThat(scanned)
                .as("@EntityScan 선언을 하나도 읽지 못했다. 목록이 비었거나 애노테이션이 사라진 것이다.")
                .isNotEmpty();

        Set<String> entities = entityClassNames();
        assertThat(entities)
                .as("persistence 모듈에서 @Entity 를 하나도 찾지 못했다. 그렇다면 이 시험은 아무것도 "
                        + "지키지 못하므로 통과로 넘기지 않는다.")
                .isNotEmpty();

        List<String> uncovered = new ArrayList<>();
        for (String entity : entities) {
            if (UNWIRED_ENTITIES.contains(entity)) continue;
            String entityPackage = ClassUtils.getPackageName(entity);
            boolean covered = scanned.stream().anyMatch(base ->
                    entityPackage.equals(base) || entityPackage.startsWith(base + "."));
            if (!covered) uncovered.add(entity);
        }

        assertThat(uncovered)
                .as("이 엔티티들이 어떤 @EntityScan 범위에도 없다. 저장 시점에 "
                        + "\"does not belong to this persistence unit\" 으로 실패한다. 해당 도메인의 "
                        + "@Configuration 에 @EntityScan 을 추가하고, 그 설정을 "
                        + "ENTITY_SCAN_DECLARERS 에도 넣는다.")
                .isEmpty();
    }

    @Test
    @DisplayName("배선되지 않은 엔티티 예외 목록이 낡지 않았다")
    void unwiredAllowListStaysCurrent() {
        // 예외 목록에 이미 사라진 엔티티가 남아 있으면, 그 항목은 아무것도 면제하지 않으면서
        // 다음 사람에게 "여기 예외가 있다" 고 잘못 알려 준다. 목록은 실재하는 엔티티만 담는다.
        Set<String> entities = entityClassNames();
        assertThat(entities)
                .as("예외 목록의 항목이 더는 @Entity 가 아니다. UNWIRED_ENTITIES 에서 뺀다.")
                .containsAll(UNWIRED_ENTITIES);
    }

    @Test
    @DisplayName("방 생성 경로의 네 엔티티가 CompetitionRoomConfiguration 에 선언되어 있다")
    void competitionRoomConfigurationDeclaresItsEntities() {
        // 위 시험은 패키지 단위로 보므로 같은 패키지의 엔티티 하나만 선언해도 통과한다.
        // 방 생성이 실제로 쓰는 네 개를 이름으로 고정해, 되돌아온 결함을 바로 지목하게 한다.
        EntityScan annotation = CompetitionRoomConfiguration.class.getAnnotation(EntityScan.class);
        assertThat(annotation)
                .as("CompetitionRoomConfiguration 에 @EntityScan 이 없다. 방을 만들 수 없게 된다.")
                .isNotNull();

        Set<String> declared = new TreeSet<>();
        for (Class<?> marker : annotation.basePackageClasses()) {
            declared.add(marker.getSimpleName());
        }
        assertThat(declared).contains(
                "CompetitionRoomJpaEntity",
                "CompetitionRoomRulesJpaEntity",
                "CompetitionLiveRoomRulesJpaEntity",
                "CompetitionRoomScheduleJpaEntity");
    }

    /** {@code @Entity} 가 붙은 모든 클래스의 이름. */
    private static Set<String> entityClassNames() {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> names = new TreeSet<>();
        for (BeanDefinition definition : provider.findCandidateComponents(PERSISTENCE_ROOT)) {
            String name = definition.getBeanClassName();
            if (name != null) names.add(name);
        }
        return names;
    }

    /** {@link #ENTITY_SCAN_DECLARERS} 가 덮는 기본 패키지. */
    private static Set<String> scannedPackages() {
        Set<String> packages = new LinkedHashSet<>();
        for (Class<?> declarer : ENTITY_SCAN_DECLARERS) {
            EntityScan annotation = declarer.getAnnotation(EntityScan.class);
            assertThat(annotation)
                    .as("%s 가 ENTITY_SCAN_DECLARERS 에 있는데 @EntityScan 이 없다. 목록에서 빼거나 "
                            + "애노테이션을 되돌린다.", declarer.getSimpleName())
                    .isNotNull();
            for (String base : annotation.basePackages()) {
                if (!base.isBlank()) packages.add(base);
            }
            for (Class<?> marker : annotation.basePackageClasses()) {
                packages.add(ClassUtils.getPackageName(marker));
            }
        }
        return packages;
    }
}
