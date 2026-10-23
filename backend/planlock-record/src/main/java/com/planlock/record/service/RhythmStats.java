package com.planlock.record.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.record.dto.RhythmInputs.*;
import com.planlock.record.dto.RhythmSummary;
import com.planlock.record.dto.RhythmSummary.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Pure projection; SQL supplies only the authenticated owner's rows. */
public final class RhythmStats {
    private RhythmStats() {}
    private record Key(long planId, LocalDate date) {}
    private record Change(LocalDate date, int status) {}
    private record Counts(int completed, int early, int missed, long micros) {
        int total() { return completed + early + missed; }
    }

    public static RhythmSummary aggregate(List<Plan> plans, List<Execution> executions, List<Behavior> behavior,
                                           LocalDate today, int days, String source, ObjectMapper json) {
        if (days < 1 || days > 366) throw new IllegalArgumentException("days must be between 1 and 366");
        var currentStart = today.minusDays(29);
        var previousStart = today.minusDays(59);
        var recentStart = today.minusDays(6);
        var previousWeekStart = today.minusDays(13);
        var rows = unique(executions, e -> e.clientId(), e -> e.id()).stream()
                .filter(e -> e.planDate() != null && !e.planDate().isAfter(today) && e.status() != null && e.status() >= 1 && e.status() <= 3).toList();
        var grouped = rows.stream().collect(Collectors.groupingBy(e -> new Key(e.planId(), e.planDate())));
        Map<Long, Plan> byId = plans.stream().collect(Collectors.toMap(Plan::id, p -> p, (a, b) -> b));
        Map<Long, List<Change>> logs = new HashMap<>();
        plans.forEach(p -> logs.put(p.id(), statusChanges(p.statusLog(), json)));
        var ids = new TreeSet<>(byId.keySet());
        rows.forEach(e -> ids.add(e.planId()));
        Map<Key, Counts> counts = new HashMap<>();
        for (int offset = -Math.max(59, days - 1); offset <= 0; offset++) {
            var date = today.plusDays(offset);
            for (long id : ids) {
                var observed = grouped.getOrDefault(new Key(id, date), List.of());
                int completed = (int) observed.stream().filter(e -> e.status() == 1).count();
                int early = (int) observed.stream().filter(e -> e.status() == 2).count();
                int excluded = (int) observed.stream().filter(e -> e.status() == 3).count();
                int expected = expected(byId.get(id), date, logs.getOrDefault(id, List.of()), json) ? 1 : 0;
                long micros = observed.stream().filter(e -> e.status() == 1 || e.status() == 2).mapToLong(RhythmStats::durationMicros).sum();
                counts.put(new Key(id, date), new Counts(completed, early, Math.max(0, expected - completed - early - excluded), micros));
            }
        }
        var tomorrow = today.plusDays(1);
        var week = ratio(values(counts, recentStart, tomorrow));
        var prevWeek = ratio(values(counts, previousWeekStart, recentStart));
        var perDay = counts.entrySet().stream().collect(Collectors.groupingBy(e -> e.getKey().date(), Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        var successful = rows.stream().filter(e -> e.status() == 1).map(Execution::planDate).collect(Collectors.toSet());
        successful.removeIf(date -> {
            var day = perDay.get(date);
            if (day != null) return day.stream().mapToInt(c -> c.early() + c.missed()).sum() > 0;
            return ids.stream().anyMatch(id -> {
                var observed = grouped.getOrDefault(new Key(id, date), List.of());
                return observed.stream().anyMatch(e -> e.status() == 2) || expected(byId.get(id), date, logs.getOrDefault(id, List.of()), json) &&
                        observed.stream().noneMatch(e -> e.status() == 1 || e.status() == 3);
            });
        });
        var cursor = successful.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (successful.contains(cursor)) { streak++; cursor = cursor.minusDays(1); }
        List<DayCell> calendar = new ArrayList<>();
        for (int offset = -(days - 1); offset <= 0; offset++) {
            var date = today.plusDays(offset);
            var day = perDay.getOrDefault(date, List.of());
            calendar.add(new DayCell(date.toString(), day.stream().mapToInt(Counts::total).sum(), day.stream().mapToInt(Counts::completed).sum(),
                    day.stream().mapToInt(Counts::early).sum(), day.stream().mapToInt(Counts::missed).sum(), minutes(day.stream().mapToLong(Counts::micros).sum()), date.equals(today)));
        }
        List<PlanPerformance> performance = new ArrayList<>();
        for (long id : ids) {
            var selected = counts.entrySet().stream().filter(e -> e.getKey().planId() == id && !e.getKey().date().isBefore(currentStart)).map(Map.Entry::getValue).toList();
            var ratio = ratio(selected);
            if (ratio.total() == 0) continue;
            var plan = byId.get(id);
            performance.add(new PlanPerformance(id, plan == null ? "已删除计划" : plan.name(), plan == null ? "" : plan.type(), ratio, ratio.completed(), ratio.total()));
        }
        performance.sort((a, b) -> {
            int comparison = Long.compare((long) a.completed() * b.total(), (long) b.completed() * a.total());
            return comparison == 0 ? Long.compare(a.planId(), b.planId()) : comparison;
        });
        int change = week.completed() - prevWeek.completed();
        String headline = rows.stream().noneMatch(e -> e.status() == 1 || e.status() == 2)
                ? "还没有执行记录，开始一个计划后这里会出现你的节奏。"
                : "近7天完成" + week.completed() + "次，比上一周期" + (change > 0 ? "多" + change + "次" : change < 0 ? "少" + -change + "次" : "持平") + "；连续达成" + streak + "天。";
        var current = values(counts, currentStart, tomorrow);
        var previous = values(counts, previousStart, currentStart);
        return new RhythmSummary(headline, streak, week, prevWeek, minutes(current.stream().mapToLong(Counts::micros).sum()),
                minutes(previous.stream().mapToLong(Counts::micros).sum()), current.stream().mapToInt(Counts::early).sum(),
                previous.stream().mapToInt(Counts::early).sum(), calendar, performance, distraction(behavior, recentStart, tomorrow), source);
    }

    private static List<Counts> values(Map<Key, Counts> counts, LocalDate start, LocalDate end) {
        return counts.entrySet().stream().filter(e -> !e.getKey().date().isBefore(start) && e.getKey().date().isBefore(end)).map(Map.Entry::getValue).toList();
    }
    private static Ratio ratio(List<Counts> values) { return new Ratio(values.stream().mapToInt(Counts::completed).sum(), values.stream().mapToInt(Counts::total).sum()); }
    private static int minutes(long micros) { return (int) Math.min(Integer.MAX_VALUE, micros / 60_000_000); }
    private static long durationMicros(Execution e) {
        return e.startedAt() == null || e.endedAt() == null ? 0 : Math.max(0, Duration.between(e.startedAt(), e.endedAt()).toNanos() / 1000);
    }
    private static List<Change> statusChanges(String raw, ObjectMapper json) {
        List<Change> result = new ArrayList<>();
        try {
            for (var item : json.readTree(raw == null ? "[]" : raw)) {
                if (item.path("status").canConvertToInt() && (item.path("status").asInt() == 0 || item.path("status").asInt() == 1)) {
                    try { result.add(new Change(LocalDate.parse(item.path("date").asText()), item.path("status").asInt())); } catch (RuntimeException ignored) { }
                }
            }
        } catch (Exception ignored) { }
        result.sort(Comparator.comparing(Change::date));
        return result;
    }
    private static boolean expected(Plan plan, LocalDate date, List<Change> log, ObjectMapper json) {
        if (plan == null) return false;
        boolean enabled = true;
        for (var change : log) {
            if (change.date().equals(date)) { enabled = true; break; }
            if (change.date().isBefore(date)) enabled = change.status() != 0;
        }
        if (!enabled) return false;
        if (plan.repeatRule() == null || plan.repeatRule().isBlank()) return true;
        try {
            var rule = json.readTree(plan.repeatRule());
            return switch (rule.path("type").asText()) {
                case "daily" -> true;
                case "weekly" -> {
                    boolean matches = false, valid = rule.path("weekdays").isArray() && !rule.path("weekdays").isEmpty();
                    for (var day : rule.path("weekdays")) {
                        if (!day.isIntegralNumber() || day.asInt() < 1 || day.asInt() > 7) valid = false;
                        if (day.asInt() == date.getDayOfWeek().getValue()) matches = true;
                    }
                    yield valid && matches;
                }
                case "monthly" -> rule.path("day").asInt() == date.getDayOfMonth();
                case "yearly" -> rule.path("month").asInt() == date.getMonthValue() && rule.path("day").asInt() == date.getDayOfMonth();
                default -> false;
            };
        } catch (Exception ignored) { return false; }
    }
    private static Distraction distraction(List<Behavior> rows, LocalDate start, LocalDate end) {
        var closed = unique(rows, b -> b.clientId(), b -> b.id()).stream().filter(b -> !"com.planlock.app".equals(b.packageName()) && b.startedAt() != null && !b.startedAt().isBefore(start.atStartOfDay()) &&
                b.startedAt().isBefore(end.atStartOfDay()) && b.endedAt() != null && !b.endedAt().isBefore(b.startedAt()) && b.durationMs() != null && b.durationMs() >= 0).toList();
        if (closed.isEmpty()) return null;
        var groups = closed.stream().collect(Collectors.groupingBy(b -> b.packageName() == null || b.packageName().isBlank() ? "unknown" : b.packageName()));
        record App(String pkg, String name, long millis) {}
        var sources = groups.entrySet().stream().map(entry -> {
            var latest = entry.getValue().stream().max(Comparator.comparing(Behavior::startedAt).thenComparing(b -> b.id() == null ? 0 : b.id())).orElseThrow();
            String name = latest.appName() == null || latest.appName().isBlank() ? entry.getKey() : latest.appName();
            return new App(entry.getKey(), name, entry.getValue().stream().mapToLong(Behavior::durationMs).sum());
        }).sorted(Comparator.comparingLong(App::millis).reversed().thenComparing(App::pkg)).limit(3)
                .map(a -> new DistractionSource(a.name(), (int) (a.millis() / 60_000))).toList();
        return new Distraction((int) (closed.stream().mapToLong(Behavior::durationMs).sum() / 60_000), sources);
    }
    private static <T> List<T> unique(List<T> rows, java.util.function.Function<T, String> client, java.util.function.Function<T, Long> id) {
        Set<String> keys = new HashSet<>(); List<T> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            T row = rows.get(i); String c = client.apply(row); Long server = id.apply(row);
            String key = c != null && !c.isBlank() ? "client:" + c : server != null && server != 0 ? "server:" + server : "unidentified:" + i;
            if (keys.add(key)) result.add(row);
        }
        return result;
    }
}
