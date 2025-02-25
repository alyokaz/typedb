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

package com.vaticle.typedb.core.concept.thing.impl;

import com.vaticle.typedb.core.common.exception.TypeDBException;
import com.vaticle.typedb.core.common.iterator.FunctionalIterator;
import com.vaticle.typedb.core.common.iterator.sorted.SortedIterator.Forwardable;
import com.vaticle.typedb.core.common.parameters.Concept.Existence;
import com.vaticle.typedb.core.common.parameters.Order;
import com.vaticle.typedb.core.concept.ConceptManager;
import com.vaticle.typedb.core.concept.thing.Relation;
import com.vaticle.typedb.core.concept.thing.Thing;
import com.vaticle.typedb.core.concept.type.RelationType;
import com.vaticle.typedb.core.concept.type.RoleType;
import com.vaticle.typedb.core.concept.type.impl.RoleTypeImpl;
import com.vaticle.typedb.core.encoding.iid.PrefixIID;
import com.vaticle.typedb.core.graph.vertex.ThingVertex;
import com.vaticle.typedb.core.graph.vertex.TypeVertex;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.vaticle.typedb.core.common.exception.ErrorMessage.ThingWrite.DELETE_ROLEPLAYER_NOT_PRESENT;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.ThingWrite.RELATION_PLAYER_MISSING;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.ThingWrite.RELATION_ROLE_UNRELATED;
import static com.vaticle.typedb.core.common.exception.ErrorMessage.ThingWrite.THING_ROLE_UNPLAYED;
import static com.vaticle.typedb.core.common.iterator.Iterators.iterate;
import static com.vaticle.typedb.core.common.iterator.Iterators.single;
import static com.vaticle.typedb.core.common.parameters.Order.Asc.ASC;
import static com.vaticle.typedb.core.common.parameters.Concept.Existence.INFERRED;
import static com.vaticle.typedb.core.common.parameters.Concept.Existence.STORED;
import static com.vaticle.typedb.core.encoding.Encoding.Edge.Thing.Base.PLAYING;
import static com.vaticle.typedb.core.encoding.Encoding.Edge.Thing.Base.RELATING;
import static com.vaticle.typedb.core.encoding.Encoding.Edge.Thing.Optimised.ROLEPLAYER;
import static com.vaticle.typedb.core.encoding.Encoding.Vertex.Thing.ROLE;

public class RelationImpl extends ThingImpl implements Relation {

    private RelationImpl(ConceptManager conceptMgr, ThingVertex vertex) {
        super(conceptMgr, vertex);
    }

    public static RelationImpl of(ConceptManager conceptMgr, ThingVertex vertex) {
        return new RelationImpl(conceptMgr, vertex);
    }

    @Override
    public RelationType getType() {
        return conceptMgr.convertRelationType(readableVertex().type());
    }

    @Override
    public void addPlayer(RoleType roleType, Thing player) {
        addPlayer(roleType, player, STORED);
    }

    @Override
    public void addPlayer(RoleType roleType, Thing player, Existence existence) {
        assert existence() == existence;
        validateIsNotDeleted();
        if (this.getType().getRelates().noneMatch(t -> t.equals(roleType))) {
            throw exception(TypeDBException.of(RELATION_ROLE_UNRELATED, this.getType().getLabel(), roleType.getLabel()));
        } else if (player.getType().getPlays().noneMatch(t -> t.equals(roleType))) {
            throw exception(TypeDBException.of(THING_ROLE_UNPLAYED, player.getType().getLabel(), roleType.getLabel().toString()));
        }

        RoleImpl role = ((RoleTypeImpl) roleType).create(existence);
        writableVertex().outs().put(RELATING, role.vertex, existence);
        ((ThingImpl) player).writableVertex().outs().put(PLAYING, role.vertex, existence);
        role.optimise();
    }

    @Override
    public void removePlayer(RoleType roleType, Thing player) {
        validateIsNotDeleted();
        Optional<ThingVertex> role = writableVertex().outs().edge(
                RELATING, PrefixIID.of(ROLE), ((RoleTypeImpl) roleType).vertex.iid()
        ).to().filter(v -> v.ins().edge(PLAYING, ((ThingImpl) player).writableVertex()) != null).first();
        if (role.isPresent()) {
            RoleImpl.of(role.get()).delete();
            deleteIfNoPlayer();
        } else {
            throw exception(TypeDBException.of(DELETE_ROLEPLAYER_NOT_PRESENT, player.getType().getLabel(), roleType.getLabel().toString()));
        }
    }

    @Override
    public void delete() {
        writableVertex().outs().edge(RELATING).to().map(RoleImpl::of).forEachRemaining(RoleImpl::delete);
        super.delete();
    }

    void deleteIfNoPlayer() {
        if (!writableVertex().outs().edge(RELATING).to().hasNext()) this.delete();
    }

    @Override
    public FunctionalIterator<Thing> getPlayers(String... roleTypes) {
        if (roleTypes.length == 0) {
            return readableVertex().outs().edge(ROLEPLAYER).to().map(v -> ThingImpl.of(conceptMgr, v));
        } else {
            return getPlayers(iterate(roleTypes).map(label -> getType().getRelates(label)).map(rt -> ((RoleTypeImpl) rt).vertex));
        }
    }

    @Override
    public Forwardable<Thing, Order.Asc> getPlayers(RoleType roleType, RoleType... roleTypes) {
        return getPlayers(single(roleType).link(iterate(roleTypes)).flatMap(RoleType::getSubtypes).distinct().map(rt -> ((RoleTypeImpl) rt).vertex));
    }

    private Forwardable<Thing, Order.Asc> getPlayers(FunctionalIterator<TypeVertex> roleTypeVertices) {
        assert roleTypeVertices.hasNext();
        return roleTypeVertices.mergeMapForwardable(v -> readableVertex().outs().edge(ROLEPLAYER, v).to(), ASC)
                .mapSorted(v -> ThingImpl.of(conceptMgr, v), thing -> ((ThingImpl) thing).readableVertex(), ASC);
    }

    @Override
    public Map<RoleTypeImpl, List<Thing>> getPlayersByRoleType() {
        Map<RoleTypeImpl, List<Thing>> playersByRole = new HashMap<>();
        getType().getRelates().forEachRemaining(rt -> {
            List<Thing> players = getPlayers(rt).toList();
            if (!players.isEmpty()) playersByRole.put((RoleTypeImpl) rt, players);
        });
        return playersByRole;
    }

    @Override
    public FunctionalIterator<RoleType> getRelating() {
        return readableVertex().outs().edge(RELATING).to().map(ThingVertex::type)
                .map(conceptMgr::convertRoleType)
                .distinct();
    }

    @Override
    public void validate() {
        super.validate();
        if (!readableVertex().outs().edge(RELATING).to().hasNext()) {
            throw exception(TypeDBException.of(RELATION_PLAYER_MISSING, getType().getLabel()));
        }
    }

    @Override
    public boolean isRelation() {
        return true;
    }

    @Override
    public RelationImpl asRelation() {
        return this;
    }
}
