/*
 * Licensed to Neo4j under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Neo4j licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.neo4j.examples.socnet;

import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;

import org.neo4j.graphalgo.BasicEvaluationContext;
import org.neo4j.graphalgo.GraphAlgoFactory;
import org.neo4j.graphalgo.PathFinder;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.graphdb.traversal.Uniqueness;
import org.neo4j.internal.helpers.collection.IterableWrapper;

import static com.google.common.collect.Iterables.addAll;
import static org.neo4j.examples.socnet.RelTypes.FRIEND;
import static org.neo4j.examples.socnet.RelTypes.NEXT;
import static org.neo4j.examples.socnet.RelTypes.STATUS;
import static org.neo4j.graphdb.Direction.BOTH;
import static org.neo4j.graphdb.PathExpanders.forTypeAndDirection;

public class Person
{
    static final String NAME = "name";

    private final GraphDatabaseService databaseService;
    private final Transaction transaction;
    // tag::the-node[]
    private final Node underlyingNode;

    Person( GraphDatabaseService databaseService, Transaction transaction, Node personNode )
    {
        this.databaseService = databaseService;
        this.transaction = transaction;
        this.underlyingNode = personNode;
    }

    protected Node getUnderlyingNode()
    {
        return underlyingNode;
    }

    // end::the-node[]

    // tag::delegate-to-the-node[]
    public String getName()
    {
        return (String)underlyingNode.getProperty( NAME );
    }

    // end::delegate-to-the-node[]

    // tag::override[]
    @Override
    public int hashCode()
    {
        return underlyingNode.hashCode();
    }

    @Override
    public boolean equals( Object o )
    {
        return o instanceof Person &&
                underlyingNode.equals( ( (Person)o ).getUnderlyingNode() );
    }

    @Override
    public String toString()
    {
        return "Person[" + getName() + "]";
    }

    // end::override[]

    public void addFriend( Person otherPerson )
    {
        if ( !this.equals( otherPerson ) )
        {
            Relationship friendRel = getFriendRelationshipTo( otherPerson );
            if ( friendRel == null )
            {
                underlyingNode.createRelationshipTo( otherPerson.getUnderlyingNode(), FRIEND );
            }
        }
    }

    public long getNrOfFriends()
    {
        return Iterables.size( getFriends() );
    }

    public Iterable<Person> getFriends()
    {
        return getFriendsByDepth( 1 );
    }

    public void removeFriend( Person otherPerson )
    {
        if ( !this.equals( otherPerson ) )
        {
            Relationship friendRel = getFriendRelationshipTo( otherPerson );
            if ( friendRel != null )
            {
                friendRel.delete();
            }
        }
    }

    public Iterable<Person> getFriendsOfFriends()
    {
        return getFriendsByDepth( 2 );
    }

    public Iterable<Person> getShortestPathTo( Person otherPerson,
                                               int maxDepth )
    {
        // use graph algo to calculate a shortest path
        PathFinder<Path> finder = GraphAlgoFactory.shortestPath( new BasicEvaluationContext( transaction, databaseService ),
                forTypeAndDirection(FRIEND, BOTH ), maxDepth );

        Path path = finder.findSinglePath( underlyingNode,
                otherPerson.getUnderlyingNode() );
        return createPersonsFromNodes( path );
    }

    public Iterable<Person> getFriendRecommendation(
            int numberOfFriendsToReturn )
    {
        HashSet<Person> friends = new HashSet<>();
        addAll( friends, getFriends() );

        HashSet<Person> friendsOfFriends = new HashSet<>();
        addAll( friendsOfFriends, getFriendsOfFriends() );

        friendsOfFriends.removeAll( friends );

        ArrayList<RankedPerson> rankedFriends = new ArrayList<>();
        for ( Person friend : friendsOfFriends )
        {
            long rank = getNumberOfPathsToPerson( friend );
            rankedFriends.add( new RankedPerson( friend, rank ) );
        }

        rankedFriends.sort(new RankedComparer());
        trimTo( rankedFriends, numberOfFriendsToReturn );

        return onlyFriend( rankedFriends );
    }

    public Iterable<StatusUpdate> getStatus()
    {
        Relationship firstStatus = underlyingNode.getSingleRelationship(
                STATUS, Direction.OUTGOING );
        if ( firstStatus == null )
        {
            return Collections.emptyList();
        }

        // tag::getStatusTraversal[]
        TraversalDescription traversal = transaction.traversalDescription()
                .depthFirst()
                .relationships( NEXT );
        // end::getStatusTraversal[]

        return new IterableWrapper<StatusUpdate, Path>(
                traversal.traverse( firstStatus.getEndNode() ) )
        {
            @Override
            protected StatusUpdate underlyingObjectToObject( Path path )
            {
                return new StatusUpdate( databaseService, transaction, path.endNode() );
            }
        };
    }

    public Iterator<StatusUpdate> friendStatuses()
    {
        return new FriendsStatusUpdateIterator( this );
    }

    public void addStatus( String text )
    {
        StatusUpdate oldStatus;
        if ( getStatus().iterator().hasNext() )
        {
            oldStatus = getStatus().iterator().next();
        } else
        {
            oldStatus = null;
        }

        Node newStatus = createNewStatusNode( text );

        if ( oldStatus != null )
        {
            underlyingNode.getSingleRelationship( RelTypes.STATUS, Direction.OUTGOING ).delete();
            newStatus.createRelationshipTo( oldStatus.getUnderlyingNode(), RelTypes.NEXT );
        }

        underlyingNode.createRelationshipTo( newStatus, RelTypes.STATUS );
    }

    private Node createNewStatusNode( String text )
    {
        Node newStatus = transaction.createNode();
        newStatus.setProperty( StatusUpdate.TEXT, text );
        newStatus.setProperty( StatusUpdate.DATE, new Date().getTime() );
        return newStatus;
    }

    private final class RankedPerson
    {
        final Person person;

        final long rank;

        private RankedPerson( Person person, long rank )
        {

            this.person = person;
            this.rank = rank;
        }

        public Person getPerson()
        {
            return person;
        }
        public long getRank()
        {
            return rank;
        }

    }

    private class RankedComparer implements Comparator<RankedPerson>
    {
        @Override
        public int compare( RankedPerson a, RankedPerson b )
        {
            return Long.compare( b.getRank(), a.getRank() );
        }

    }

    private void trimTo( ArrayList<RankedPerson> rankedFriends,
                         int numberOfFriendsToReturn )
    {
        while ( rankedFriends.size() > numberOfFriendsToReturn )
        {
            rankedFriends.remove( rankedFriends.size() - 1 );
        }
    }

    private Iterable<Person> onlyFriend( Iterable<RankedPerson> rankedFriends )
    {
        ArrayList<Person> retVal = new ArrayList<>();
        for ( RankedPerson person : rankedFriends )
        {
            retVal.add( person.getPerson() );
        }
        return retVal;
    }

    private Relationship getFriendRelationshipTo( Person otherPerson )
    {
        Node otherNode = otherPerson.getUnderlyingNode();
        for ( Relationship rel : underlyingNode.getRelationships( FRIEND ) )
        {
            if ( rel.getOtherNode( underlyingNode ).equals( otherNode ) )
            {
                return rel;
            }
        }
        return null;
    }

    private Iterable<Person> getFriendsByDepth( int depth )
    {
        // return all my friends and their friends using new traversal API
        TraversalDescription travDesc = transaction.traversalDescription()
                .breadthFirst()
                .relationships( FRIEND )
                .uniqueness( Uniqueness.NODE_GLOBAL )
                .evaluator( Evaluators.toDepth( depth ) )
                .evaluator( Evaluators.excludeStartPosition() );

        return createPersonsFromPath( travDesc.traverse( underlyingNode ) );
    }

    private IterableWrapper<Person, Path> createPersonsFromPath(
            Traverser iterableToWrap )
    {
        return new IterableWrapper<Person, Path>( iterableToWrap )
        {
            @Override
            protected Person underlyingObjectToObject( Path path )
            {
                return new Person( databaseService, transaction, path.endNode() );
            }
        };
    }

    private long getNumberOfPathsToPerson( Person otherPerson )
    {
        PathFinder<Path> finder = GraphAlgoFactory.allPaths( new BasicEvaluationContext( transaction, databaseService ),
                forTypeAndDirection( FRIEND, BOTH ), 2 );
        Iterable<Path> paths = finder.findAllPaths( getUnderlyingNode(), otherPerson.getUnderlyingNode() );
        return Iterables.size( paths );
    }

    private Iterable<Person> createPersonsFromNodes( final Path path )
    {
        return new IterableWrapper<>( path.nodes() )
        {
            @Override
            protected Person underlyingObjectToObject( Node node )
            {
                return new Person( databaseService, transaction, node );
            }
        };
    }
}
