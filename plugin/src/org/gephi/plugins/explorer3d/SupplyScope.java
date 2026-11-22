package org.gephi.plugins.explorer3d;

import java.util.*;

record SupplyScope(Set<String> nodes, Set<String> edges) {
    private record Step(int neighbor, Scene.Link edge) { }

    static String directionLabel(String direction) {
        return switch (direction) {
            case "upstream" -> "供应商";
            case "downstream" -> "客户";
            default -> "双向关系";
        };
    }

    static SupplyScope create(Scene scene) {
        Set<String> firms = new LinkedHashSet<>();
        for (Scene.Vertex node : scene.nodes) if (scene.neighborhoodRoots.contains(node.id)) firms.add(node.id);
        Set<String> relationships = new LinkedHashSet<>();
        collectYear(scene, scene.activeYear, firms, relationships);
        if (scene.referenceYear != 0) collectYear(scene, scene.referenceYear, firms, relationships);
        return new SupplyScope(Set.copyOf(firms), Set.copyOf(relationships));
    }

    private static void collectYear(Scene scene, int year, Set<String> firms, Set<String> relationships) {
        if (!scene.neighborhoodDirection.equals("downstream")) collect(scene, year, true, firms, relationships);
        if (!scene.neighborhoodDirection.equals("upstream")) collect(scene, year, false, firms, relationships);
    }

    private static void collect(Scene scene, int year, boolean upstream, Set<String> firms, Set<String> relationships) {
        List<List<Step>> adjacency = new ArrayList<>(scene.nodes.size());
        Set<Integer> frontier = new LinkedHashSet<>();
        for (int i = 0; i < scene.nodes.size(); i++) {
            adjacency.add(new ArrayList<>());
            if (scene.neighborhoodRoots.contains(scene.nodes.get(i).id)) frontier.add(i);
        }
        for (Scene.Link link : scene.edges) if (link.directed && scene.edgeInYear(link, year, scene.minWeight)) {
            int from = upstream ? link.target : link.source;
            int to = upstream ? link.source : link.target;
            adjacency.get(from).add(new Step(to, link));
        }
        Set<Integer> reached = new HashSet<>(frontier);
        for (int depth = 0; depth < scene.neighborhoodHops && !frontier.isEmpty(); depth++) {
            Set<Integer> next = new LinkedHashSet<>();
            for (int from : frontier) for (Step step : adjacency.get(from)) {
                if (reached.contains(step.neighbor)) {
                    if (depth == 0 && frontier.contains(step.neighbor)) relationships.add(step.edge.id);
                    continue;
                }
                next.add(step.neighbor);
                firms.add(scene.nodes.get(step.neighbor).id);
                relationships.add(step.edge.id);
            }
            reached.addAll(next);
            frontier = next;
        }
    }

    Map<String, Object> report(Scene scene) {
        int firms = 0, mapped = 0;
        Map<String, Integer> blocked = new LinkedHashMap<>();
        for (Scene.Vertex node : scene.nodes) {
            if (!nodes.contains(node.id)) continue;
            if (!scene.neighborhoodRoots.contains(node.id)) {
                firms++;
                if (scene.geography != null && scene.geography.positions.containsKey(node.id)) mapped++;
            }
            for (String criterion : scene.blockers(node)) blocked.merge(criterion, 1, Integer::sum);
        }
        return Map.of("direction", scene.neighborhoodDirection, "hops", scene.neighborhoodHops,
            "roots", scene.neighborhoodRoots, "related_firms", firms, "links", edges.size(),
            "map_available", scene.geography != null, "mapped_firms", mapped,
            "unmapped_firms", scene.geography == null ? 0 : firms - mapped, "blocked", blocked);
    }
}
