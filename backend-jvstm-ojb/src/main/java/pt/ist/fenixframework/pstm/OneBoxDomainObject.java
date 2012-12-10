package pt.ist.fenixframework.pstm;

import java.io.ObjectStreamException;
import java.io.Serializable;

import jvstm.util.Cons;
import jvstm.util.Pair;
import pt.ist.fenixframework.core.DomainObjectAllocator;
import pt.ist.fenixframework.dml.runtime.Relation;

/**
 * The OneBoxDomainObject class is meant to be used as a superclass of
 * DomainObjects generated by the DML compiler that use a single box
 * (defined in this class) to hold the entire state of a DomainObject.
 *
 * This will reduce the amount of memory needed to store each object
 * (because there are less overheads caused by the vboxes), at the
 * potential cost of increasing the likelihood of conflicts, because
 * now the conflict detection granularity is at the object level,
 * rather than at the slot level.
 *
 * This class uses also a lazy approach to the materialization of
 * RelationLists for holding the set of objects that relate to an
 * object, so that unused RelationLists don't consume memory
 * unnecessary.
 */

public abstract class OneBoxDomainObject extends AbstractDomainObject {

    // box holding the entire state of the object
    private final VState obj$state;
    private static final String OBJ_STATE_SLOT_NAME = "obj$state";

    // we need to know whether this instance of the object was created
    // to materialize an already existing object that is being read
    // from the persistent store or if it is a new object
    private final boolean isNewObject;

    // Collection of RelationLists that are created lazilly
    private volatile Cons<Pair<String,RelationList>> relationLists = Cons.empty();


    // This is the constructor used when a new DomainObject is created
    protected OneBoxDomainObject() {
        super();
        this.obj$state = VState.makeNew(this, OBJ_STATE_SLOT_NAME, false);
        this.obj$state.put(this, OBJ_STATE_SLOT_NAME, make$newState());
        this.isNewObject = true;
        create$allLists();
    }

    // This is the constructor used as part of the allocate-instance
    // protocol, when a DomainObject is being read from the persistent
    // store
    protected OneBoxDomainObject(DomainObjectAllocator.OID oid) {
        super(oid);
        this.obj$state = VState.makeNew(this, OBJ_STATE_SLOT_NAME, true);
        this.isNewObject = false;
    }

    // each class will have to implement/override this method to
    // create an instance of the appropriate subclass of DO_State
    protected abstract DO_State make$newState();

    // each class will have to override this method to create all of
    // the lists corresponding to relations when a new instance is
    // created
    protected void create$allLists() {
    }

    protected final DO_State get$obj$state(boolean forWriting) {
        DO_State currState = (DO_State)this.obj$state.get(this, OBJ_STATE_SLOT_NAME);
        if (forWriting && currState.committed) {
            DO_State newState = make$newState();
            currState.copyTo(newState);
            this.obj$state.put(this, OBJ_STATE_SLOT_NAME, newState);
            return newState;
        } else {
            return currState;
        }
    }

    protected void readSlotsFromResultSet(java.sql.ResultSet rs, int txNumber) throws java.sql.SQLException {
        throw new Error("readSlotsFromResultSet should not be used for OneBoxDomainObjects");
    }
    
    @Override
    public final void readFromResultSet(java.sql.ResultSet rs) throws java.sql.SQLException {
	int txNumber = Transaction.current().getNumber();
        DO_State loadedState = make$newState();

	readStateFromResultSet(rs, loadedState);
        // this state was already committed and is being read now
        loadedState.markCommitted();

        obj$state.persistentLoad(loadedState, txNumber);
    }

    protected abstract void readStateFromResultSet(java.sql.ResultSet rs, DO_State state) throws java.sql.SQLException;


    public abstract static class DO_State implements Serializable {
        private boolean committed = false;

        void markCommitted() {
            this.committed = true;
        }

        protected void copyTo(DO_State newState) {
            // there is nothing to copy at this level
        }

	// serialization code
	protected Object writeReplace() throws ObjectStreamException {
	    throw new RuntimeException("writeReplace not implemented at this level");
	}

	protected abstract static class SerializedForm implements Serializable {
	    private static final long serialVersionUID = 1L;

	    protected SerializedForm(DO_State obj) {
		// there are no slots to serialize at this level
	    }

	    Object readResolve() throws ObjectStreamException {
		throw new RuntimeException("readResolve not implemented at this level");
	    }

	    // subclasses must implement this method to set in the parameter the de-serialized
	    // fields from the SerializedForm
	    protected void fillInState(DO_State obj) {
		obj.markCommitted();
		// nothing to fill-in at this level
	    }
	}
    }


    @Override
    VersionedSubject getSlotNamed(String attrName) {
        if (attrName.equals(OBJ_STATE_SLOT_NAME)) {
            return obj$state;
        }

        Relation rel = get$$relationFor(attrName);
        if (rel != null) {
            return get$$relationList(attrName, rel);
        } else {
            return super.getSlotNamed(attrName);
        }
    }


    // In the following, we have the methods that handle the lazy creation of the RelationLists

    protected RelationList get$$relationList(String attrName, Relation relation) {
        Cons<Pair<String,RelationList>> allLists = relationLists;
        RelationList list = findRelationList(allLists, Cons.empty(), attrName);
        if (list != null) {
            return list;
        }

        // if we haven't found the list, then we need to create it first
        // but we need to ensure that no one is racing to do the same
        synchronized (this) {
            // read the list of relationLists again to see if someone
            // may have changed it in the meanwhile
            Cons<Pair<String,RelationList>> currentLists = relationLists;

            if (currentLists != allLists) {
                // we need to see again if a new list was created
                list = findRelationList(currentLists, allLists, attrName);
                if (list != null) {
                    return list;
                }
            }

            // we really need to create the list
            RelationList newList = new RelationList(this, relation, attrName, (! isNewObject));
            relationLists = currentLists.cons(new Pair<String,RelationList>(attrName, newList));
            return newList;
        }
    }

    private static RelationList findRelationList(Cons<Pair<String,RelationList>> allLists, Object lastList, String attrName) {
        while (allLists != lastList) {
            Pair<String,RelationList> list = allLists.first();
            // it is not safe to use == instead of equals(Object) to
            // compare Strings here because this method may be
            // called with non-interned strings (when reading the changelogs from DB)
            if (list.first.equals(attrName)) {
                return list.second;
            }
            allLists = allLists.rest();
        }

        return null;
    }

    protected Relation get$$relationFor(String attrName) {
        return null;
    }
}
