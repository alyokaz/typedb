/*
 * Copyright (C) 2022 Vaticle
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.vaticle.typedb.core.traversal.procedure;

import com.vaticle.typedb.core.common.exception.TypeDBException;
import com.vaticle.typedb.core.common.iterator.FunctionalIterator;
import com.vaticle.typedb.core.common.parameters.Label;
import com.vaticle.typedb.core.common.parameters.Order;
import com.vaticle.typedb.core.concurrent.producer.FunctionalProducer;
import com.vaticle.typedb.core.encoding.Encoding;
import com.vaticle.typedb.core.graph.GraphManager;
import com.vaticle.typedb.core.traversal.Traversal;
import com.vaticle.typedb.core.traversal.common.Identifier;
import com.vaticle.typedb.core.traversal.common.Modifiers;
import com.vaticle.typedb.core.traversal.common.VertexMap;
import com.vaticle.typedb.core.traversal.graph.TraversalVertex;
import com.vaticle.typedb.core.traversal.planner.ComponentPlanner;
import com.vaticle.typedb.core.traversal.planner.GraphPlanner;
import com.vaticle.typedb.core.traversal.planner.PlannerEdge;
import com.vaticle.typedb.core.traversal.planner.PlannerVertex;
import com.vaticle.typedb.core.traversal.predicate.Predicate;
import com.vaticle.typedb.core.traversal.scanner.GraphIterator;
import com.vaticle.typedb.core.traversal.structure.Structure;
import com.vaticle.typedb.core.traversal.structure.StructureEdge;
import com.vaticle.typedb.core.traversal.structure.StructureVertex;
import com.vaticle.typeql.lang.common.TypeQLToken.Annotation;
import com.vaticle.typeql.lang.pattern.variable.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.vaticle.typedb.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static com.vaticle.typedb.core.common.iterator.Iterators.iterate;
import static com.vaticle.typedb.core.common.parameters.Order.Asc.ASC;
import static com.vaticle.typedb.core.concurrent.producer.Producers.async;
import static java.util.Comparator.comparing;

public class GraphProcedure implements PermutationProcedure {

    private static final Logger LOG = LoggerFactory.getLogger(GraphProcedure.class);

    private final ProcedureVertex<?, ?>[] vertices;
    private Set<ProcedureVertex<?, ?>> startVertices;
    private Set<ProcedureVertex<?, ?>> endVertices;

    private GraphProcedure(ProcedureVertex<?, ?>[] vertices) {
        this.vertices = vertices;
    }

    public static GraphProcedure create(List<ComponentPlanner> planners) {
        Builder builder = new Builder();
        planners.forEach(p -> {
            if (p.isGraph()) builder.register(p.asGraph(), builder.vertices.size());
            else builder.registerVertex(p.asVertex().structureVertex(), builder.vertices.size());
        });
        return builder.build();
    }

    public static GraphProcedure create(Structure structure, Map<Identifier, Integer> orders) {
        Builder builder = new Builder();
        builder.register(structure, orders);
        return builder.build();
    }

    public ProcedureVertex<?, ?>[] vertices() {
        return vertices;
    }

    public ProcedureVertex<?, ?> initialVertex() {
        return vertices[0];
    }

    public ProcedureVertex<?, ?> lastVertex() {
        return vertices[vertices.length - 1];
    }

    public Set<ProcedureVertex<?, ?>> endVertices() {
        if (endVertices == null) {
            endVertices = iterate(vertices()).filter(v -> v.outs().isEmpty()).toSet();
        }
        return endVertices;
    }

    public ProcedureVertex<?, ?> vertex(int pos) {
        assert 0 <= pos && pos < vertices.length;
        return vertices[pos];
    }

    public int vertexCount() {
        return vertices.length;
    }

    @Override
    public FunctionalProducer<VertexMap> producer(GraphManager graphMgr, Traversal.Parameters params,
                                                  Modifiers modifiers, int parallelisation) {
        if (LOG.isTraceEnabled()) {
            LOG.trace(params.toString());
            LOG.trace(this.toString());
        }
        Optional<Order> order = modifiers.sorting().order(initialVertex().id());
        boolean sortByValue = order.isPresent();
        if (initialVertex().id().isRetrievable() && modifiers.filter().variables().contains(initialVertex().id().asVariable().asRetrievable())) {
            return async(initialVertex().iterator(graphMgr, params, order.orElse(ASC), sortByValue)
                    // TODO we can reduce the size of the distinct() set if the traversal engine doesn't overgenerate as much
                    .map(v -> new GraphIterator(graphMgr, v, this, params, modifiers).distinct()), parallelisation);
        } else {
            return async(initialVertex().iterator(graphMgr, params, order.orElse(ASC), sortByValue)
                    .map(v -> new GraphIterator(graphMgr, v, this, params, modifiers)), parallelisation)
                    // TODO we can reduce the size of the distinct() set if the traversal engine doesn't overgenerate as much
                    .distinct();
        }
    }

    @Override
    public FunctionalIterator<VertexMap> iterator(GraphManager graphMgr, Traversal.Parameters params,
                                                  Modifiers modifiers) {
        if (LOG.isTraceEnabled()) {
            LOG.trace(params.toString());
            LOG.trace(this.toString());
        }
        Optional<Order> order = modifiers.sorting().order(initialVertex().id());
        boolean sortByValue = order.isPresent();
        if (initialVertex().id().isRetrievable() && modifiers.filter().variables().contains(initialVertex().id().asVariable().asRetrievable())) {
            return initialVertex().iterator(graphMgr, params, order.orElse(ASC), sortByValue)
                    // TODO we can reduce the size of the distinct() set if the traversal engine doesn't overgenerate as much
                    .flatMap(v -> new GraphIterator(graphMgr, v, this, params, modifiers).distinct());
        } else {
            // TODO we can reduce the size of the distinct() set if the traversal engine doesn't overgenerate as much
            return initialVertex().iterator(graphMgr, params, order.orElse(ASC), sortByValue)
                    .flatMap(v -> new GraphIterator(graphMgr, v, this, params, modifiers))
                    // TODO we can reduce the size of the distinct() set if the traversal engine doesn't overgenerate as much
                    .distinct();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GraphProcedure that = (GraphProcedure) o;
        return Arrays.equals(vertices, that.vertices);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(vertices);
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("Graph Procedure: {");
        for (int i = 0; i < vertexCount(); i++) {
            ProcedureVertex<?, ?> vertex = vertices[i];
            str.append("\n\t").append(vertex);
            for (ProcedureEdge<?, ?> edge : vertex.ins()) {
                str.append("\n\t\t\t").append(edge);
            }
        }
        str.append("\n}");
        return str.toString();
    }

    public static class Builder {

        private final Map<Identifier, ProcedureVertex<?, ?>> vertices;

        public Builder() {
            this.vertices = new HashMap<>();
        }

        public GraphProcedure build() {
            return new GraphProcedure(vertices.values().stream().sorted(comparing(ProcedureVertex::order))
                    .toArray(ProcedureVertex[]::new));
        }

        private void register(GraphPlanner planner, int startOrder) {
            assert iterate(vertices.values()).allMatch(v -> v.order() < startOrder);
            planner.vertices().forEach(id -> registerVertex(planner.vertex(id), startOrder + planner.vertex(id).getOrder()));
            planner.vertices().forEach(id -> registerEdges(planner.vertex(id)));
        }

        private void registerEdges(PlannerVertex<?> plannerVertex) {
            plannerVertex.outs().forEach(plannerEdge -> {
                if (plannerEdge.isSelected()) registerEdge(plannerEdge);
            });
            plannerVertex.ins().forEach(plannerEdge -> {
                if (plannerEdge.isSelected()) registerEdge(plannerEdge);
            });
            plannerVertex.loops().forEach(plannerEdge -> {
                if (plannerEdge.direction().isForward()) registerEdge(plannerEdge);
            });
        }

        private void registerEdge(PlannerEdge.Directional<?, ?> plannerEdge) {
            ProcedureVertex<?, ?> from = vertex(plannerEdge.from());
            ProcedureVertex<?, ?> to = vertex(plannerEdge.to());
            ProcedureEdge<?, ?> edge = ProcedureEdge.of(from, to, plannerEdge);
            attachEdge(from, to, edge);
        }

        private void register(Structure structure, Map<Identifier, Integer> orders) {
            assert iterate(structure.vertices()).allMatch(v -> orders.containsKey(v.id())) &&
                    iterate(structure.vertices()).allMatch(v ->
                            !vertices.containsKey(v.id()) || vertices.get(v.id()).order() == orders.get(v.id())
                    );
            structure.vertices().forEach(vertex -> registerVertex(vertex, orders.get(vertex.id())));
            structure.vertices().forEach(this::registerEdges);
        }

        private void registerEdges(StructureVertex<?> structureVertex) {
            assert vertices.containsKey(structureVertex.id());
            structureVertex.outs().forEach(e -> registerEdge(e, true));
            structureVertex.ins().forEach(e -> registerEdge(e, false));
            structureVertex.loops().forEach(e -> registerEdge(e, true));
        }

        private void registerEdge(StructureEdge<?, ?> structureEdge, boolean isForward) {
            ProcedureVertex<?, ?> from = vertex(structureEdge.from());
            ProcedureVertex<?, ?> to = vertex(structureEdge.to());
            if (!isForward) {
                ProcedureVertex<?, ?> tmp = to;
                to = from;
                from = tmp;
            }
            if (from.order() > to.order()) return;
            ProcedureEdge<?, ?> edge = ProcedureEdge.of(from, to, structureEdge, isForward);
            attachEdge(from, to, edge);
        }

        public void attachEdge(ProcedureVertex<?, ?> from, ProcedureVertex<?, ?> to, ProcedureEdge<?, ?> edge) {
            assert from.equals(edge.from()) && to.equals(edge.to());
            if (from.equals(to)) {
                from.loop(edge);
            } else {
                from.out(edge);
                to.in(edge);
            }
        }

        private void registerVertex(TraversalVertex<?, ?> traversalVertex, int order) {
            if (traversalVertex.isThing()) {
                ProcedureVertex.Thing vertex = registerThingVertex(traversalVertex.id());
                vertex.props(traversalVertex.props().asThing());
                vertex.setOrder(order);
            } else if (traversalVertex.isType()) {
                ProcedureVertex.Type vertex = registerTypeVertex(traversalVertex.id());
                vertex.props(traversalVertex.props().asType());
                vertex.setOrder(order);
            } else if (traversalVertex.isValue()) {
                ProcedureVertex.Value vertex = registerValueVertex(traversalVertex.id());
                vertex.props(traversalVertex.props().asValue());
                vertex.setOrder(order);
            }
        }

        private ProcedureVertex.Thing registerThingVertex(Identifier id) {
            assert !vertices.containsKey(id);
            ProcedureVertex.Thing vertex = new ProcedureVertex.Thing(id);
            vertices.put(id, vertex);
            return vertex;
        }

        private ProcedureVertex.Type registerTypeVertex(Identifier id) {
            assert !vertices.containsKey(id);
            ProcedureVertex.Type vertex = new ProcedureVertex.Type(id);
            vertices.put(id, vertex);
            return vertex;
        }

        private ProcedureVertex.Value registerValueVertex(Identifier id) {
            assert !vertices.containsKey(id);
            ProcedureVertex.Value vertex = new ProcedureVertex.Value(id);
            vertices.put(id, vertex);
            return vertex;
        }

        private ProcedureVertex<?, ?> vertex(TraversalVertex<?, ?> traversalVertex) {
            if (traversalVertex.isThing()) return vertices.get(traversalVertex.id()).asThing();
            else if (traversalVertex.isType()) return vertices.get(traversalVertex.id()).asType();
            else if (traversalVertex.isValue()) return vertices.get(traversalVertex.id()).asValue();
            else throw TypeDBException.of(ILLEGAL_STATE);
        }

        // ---- manual builder methods ----

        public ProcedureVertex.Type labelledType(int order, String label) {
            ProcedureVertex.Type vertex = registerTypeVertex(Identifier.Variable.of(Reference.label(label)));
            vertex.setOrder(order);
            return vertex;
        }

        public ProcedureVertex.Type namedType(int order, String name) {
            ProcedureVertex.Type vertex = registerTypeVertex(Identifier.Variable.of(Reference.concept(name)));
            vertex.setOrder(order);
            return vertex;
        }

        public ProcedureVertex.Thing namedThing(int order, String name) {
            ProcedureVertex.Thing vertex = registerThingVertex(Identifier.Variable.of(Reference.concept(name)));
            vertex.setOrder(order);
            return vertex;
        }

        public ProcedureVertex.Thing anonymousThing(int order, int id) {
            ProcedureVertex.Thing vertex = registerThingVertex(Identifier.Variable.anon(id));
            vertex.setOrder(order);
            return vertex;
        }

        public ProcedureVertex.Thing scopedThing(int order, ProcedureVertex.Thing relation, @Nullable ProcedureVertex.Type roleType, ProcedureVertex.Thing player, int repetition) {
            ProcedureVertex.Thing vertex = registerThingVertex(Identifier.Scoped.of(relation.id().asVariable(), roleType != null ? roleType.id().asVariable() : null, player.id().asVariable(), repetition));
            vertex.setOrder(order);
            return vertex;
        }

        public ProcedureVertex.Value namedValue(int order, String name) {
            ProcedureVertex.Value vertex = registerValueVertex(Identifier.Variable.of(Reference.value(name)));
            vertex.setOrder(order);
            return vertex;
        }

        public ProcedureVertex.Type setLabel(ProcedureVertex.Type type, Label label) {
            type.props().labels(label);
            return type;
        }

        public ProcedureVertex.Type setLabels(ProcedureVertex.Type type, Set<Label> labels) {
            type.props().labels(labels);
            return type;
        }

        public ProcedureVertex.Thing setPredicate(ProcedureVertex.Thing thing, Predicate.Value.String predicate) {
            thing.props().predicate(predicate);
            return thing;
        }

        public ProcedureEdge.Native.Type.Sub.Forward forwardSub(
                ProcedureVertex.Type child, ProcedureVertex.Type parent, boolean isTransitive) {
            ProcedureEdge.Native.Type.Sub.Forward edge =
                    new ProcedureEdge.Native.Type.Sub.Forward(child, parent, isTransitive);
            attachEdge(child, parent, edge);
            return edge;
        }

        public ProcedureEdge.Native.Type.Sub.Backward backwardSub(
                ProcedureVertex.Type parent, ProcedureVertex.Type child, boolean isTransitive) {
            ProcedureEdge.Native.Type.Sub.Backward edge =
                    new ProcedureEdge.Native.Type.Sub.Backward(parent, child, isTransitive);
            attachEdge(parent, child, edge);
            return edge;
        }

        public ProcedureEdge.Native.Type.Plays.Forward forwardPlays(
                ProcedureVertex.Type player, ProcedureVertex.Type roleType) {
            ProcedureEdge.Native.Type.Plays.Forward edge =
                    new ProcedureEdge.Native.Type.Plays.Forward(player, roleType);
            attachEdge(player, roleType, edge);
            return edge;
        }

        public ProcedureEdge.Native.Type.Plays.Backward backwardPlays(
                ProcedureVertex.Type roleType, ProcedureVertex.Type player) {
            ProcedureEdge.Native.Type.Plays.Backward edge =
                    new ProcedureEdge.Native.Type.Plays.Backward(roleType, player);
            attachEdge(roleType, player, edge);
            return edge;
        }

        public ProcedureEdge.Native.Type.Owns.Forward forwardOwns(
                ProcedureVertex.Type owner, ProcedureVertex.Type att, Set<Annotation> annotations) {
            ProcedureEdge.Native.Type.Owns.Forward edge =
                    new ProcedureEdge.Native.Type.Owns.Forward(owner, att, annotations);
            attachEdge(owner, att, edge);
            return edge;
        }

        public ProcedureEdge.Native.Type.Owns.Backward backwardOwns(
                ProcedureVertex.Type att, ProcedureVertex.Type owner, Set<Annotation> annotations) {
            ProcedureEdge.Native.Type.Owns.Backward edge =
                    new ProcedureEdge.Native.Type.Owns.Backward(att, owner, annotations);
            attachEdge(att, owner, edge);
            return edge;
        }

        public ProcedureEdge.Equal forwardEqual(ProcedureVertex.Type from, ProcedureVertex.Type to) {
            ProcedureEdge.Equal edge = new ProcedureEdge.Equal(from, to, Encoding.Direction.Edge.FORWARD);
            attachEdge(from, to, edge);
            return edge;
        }

        public ProcedureEdge.Equal backwardEqual(ProcedureVertex.Type from, ProcedureVertex.Type to) {
            ProcedureEdge.Equal edge = new ProcedureEdge.Equal(from, to, Encoding.Direction.Edge.BACKWARD);
            attachEdge(from, to, edge);
            return edge;
        }

        public ProcedureEdge.Predicate forwardPredicate(ProcedureVertex.Thing from, ProcedureVertex.Thing to, Predicate.Variable predicate) {
            ProcedureEdge.Predicate edge = new ProcedureEdge.Predicate(from, to, Encoding.Direction.Edge.FORWARD, predicate);
            attachEdge(from, to, edge);
            return edge;
        }

        public ProcedureEdge.Predicate backwardPredicate(ProcedureVertex.Thing from, ProcedureVertex.Thing to, Predicate.Variable predicate) {
            ProcedureEdge.Predicate edge = new ProcedureEdge.Predicate(from, to, Encoding.Direction.Edge.BACKWARD, predicate);
            attachEdge(from, to, edge);
            return edge;
        }

        public ProcedureEdge.Native.Isa.Forward forwardIsa(
                ProcedureVertex.Thing thing, ProcedureVertex.Type type, boolean isTransitive) {
            ProcedureEdge.Native.Isa.Forward edge =
                    new ProcedureEdge.Native.Isa.Forward(thing, type, isTransitive);
            attachEdge(thing, type, edge);
            return edge;
        }

        public ProcedureEdge.Native.Isa.Backward backwardIsa(
                ProcedureVertex.Type type, ProcedureVertex.Thing thing, boolean isTransitive) {
            ProcedureEdge.Native.Isa.Backward edge =
                    new ProcedureEdge.Native.Isa.Backward(type, thing, isTransitive);
            attachEdge(type, thing, edge);
            return edge;
        }

        public ProcedureEdge.Native.Type.Relates.Forward forwardRelates(
                ProcedureVertex.Type relationType, ProcedureVertex.Type roleType) {
            ProcedureEdge.Native.Type.Relates.Forward edge =
                    new ProcedureEdge.Native.Type.Relates.Forward(relationType, roleType);
            attachEdge(relationType, roleType, edge);
            return edge;
        }

        public ProcedureEdge.Native.Type.Relates.Backward backwardRelates(
                ProcedureVertex.Type roleType, ProcedureVertex.Type relationType) {
            ProcedureEdge.Native.Type.Relates.Backward edge =
                    new ProcedureEdge.Native.Type.Relates.Backward(roleType, relationType);
            attachEdge(roleType, relationType, edge);
            return edge;
        }

        public ProcedureEdge.Native.Thing.Has.Forward forwardHas(
                ProcedureVertex.Thing owner, ProcedureVertex.Thing attribute) {
            ProcedureEdge.Native.Thing.Has.Forward edge =
                    new ProcedureEdge.Native.Thing.Has.Forward(owner, attribute);
            attachEdge(owner, attribute, edge);
            return edge;
        }

        public ProcedureEdge.Native.Thing.Has.Backward backwardHas(
                ProcedureVertex.Thing attribute, ProcedureVertex.Thing owner) {
            ProcedureEdge.Native.Thing.Has.Backward edge =
                    new ProcedureEdge.Native.Thing.Has.Backward(attribute, owner);
            attachEdge(attribute, owner, edge);
            return edge;
        }

        public ProcedureEdge.Native.Thing.Relating.Forward forwardRelating(
                ProcedureVertex.Thing relation, ProcedureVertex.Thing role) {
            ProcedureEdge.Native.Thing.Relating.Forward edge =
                    new ProcedureEdge.Native.Thing.Relating.Forward(relation, role);
            attachEdge(relation, role, edge);
            return edge;
        }

        public ProcedureEdge.Native.Thing.Relating.Backward backwardRelating(
                ProcedureVertex.Thing role, ProcedureVertex.Thing relation) {
            ProcedureEdge.Native.Thing.Relating.Backward edge =
                    new ProcedureEdge.Native.Thing.Relating.Backward(role, relation);
            attachEdge(role, relation, edge);
            return edge;
        }

        public ProcedureEdge.Native.Thing.Playing.Forward forwardPlaying(
                ProcedureVertex.Thing player, ProcedureVertex.Thing role) {
            ProcedureEdge.Native.Thing.Playing.Forward edge =
                    new ProcedureEdge.Native.Thing.Playing.Forward(player, role);
            attachEdge(player, role, edge);
            return edge;
        }

        public ProcedureEdge.Native.Thing.Playing.Backward backwardPlaying(
                ProcedureVertex.Thing role, ProcedureVertex.Thing player) {
            ProcedureEdge.Native.Thing.Playing.Backward edge =
                    new ProcedureEdge.Native.Thing.Playing.Backward(role, player);
            attachEdge(role, player, edge);
            return edge;
        }

        public ProcedureEdge.Native.Thing.RolePlayer.Forward forwardRolePlayer(
                ProcedureVertex.Thing relation, ProcedureVertex.Thing player, int repetition, Set<Label> roleTypes) {
            ProcedureEdge.Native.Thing.RolePlayer.Forward edge =
                    new ProcedureEdge.Native.Thing.RolePlayer.Forward(relation, player, repetition, roleTypes);
            attachEdge(relation, player, edge);
            return edge;
        }

        public ProcedureEdge.Native.Thing.RolePlayer.Backward backwardRolePlayer(
                ProcedureVertex.Thing player, ProcedureVertex.Thing relation, int repetition, Set<Label> roleTypes) {
            ProcedureEdge.Native.Thing.RolePlayer.Backward edge =
                    new ProcedureEdge.Native.Thing.RolePlayer.Backward(player, relation, repetition, roleTypes);
            attachEdge(player, relation, edge);
            return edge;
        }

        public ProcedureEdge.Argument forwardArgument(ProcedureVertex<?,?> argument, ProcedureVertex.Value result) {
            // backwardArgument is illegal
            ProcedureEdge.Argument edge = new ProcedureEdge.Argument(argument, result, Encoding.Direction.Edge.FORWARD);
            attachEdge(argument, result, edge);
            return edge;
        }
    }
}
