package pt.ist.fenixframework.backend.ogm;

import java.util.concurrent.Callable;
import java.util.HashMap;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.ejb.AvailableSettings;
import org.hibernate.ejb.HibernateEntityManagerFactory;
import org.hibernate.service.jta.platform.spi.JtaPlatform;

import org.infinispan.CacheException;

import pt.ist.fenixframework.Atomic;
import pt.ist.fenixframework.TransactionManager;

public class OgmTransactionManager implements TransactionManager {
    private static final Logger logger = LoggerFactory.getLogger(OgmTransactionManager.class);

    private javax.transaction.TransactionManager delegateTxManager;

    EntityManagerFactory emf;

    private boolean booting = false;

    void setupTxManager(OgmConfig config) {
        booting = true;
        HashMap properties = new HashMap();
        properties.put(AvailableSettings.INTERCEPTOR, AllocationInterceptor.class.getName());

        emf = Persistence.createEntityManagerFactory("fenixframework-persistence-unit", properties);
        SessionFactoryImplementor sessionFactory =
            (SessionFactoryImplementor)((HibernateEntityManagerFactory)emf).getSessionFactory();
        delegateTxManager = sessionFactory.getServiceRegistry().getService(JtaPlatform.class).
            retrieveTransactionManager();
        booting = false;
    }

    boolean isBooting() {
        return booting;
    }

    private final ThreadLocal<EntityManager> currentEntityManager = new ThreadLocal<EntityManager>();

    EntityManager getEntityManager() {
        return currentEntityManager.get();
    }

    @Override
    public void begin() throws NotSupportedException, SystemException {
        logger.info("Begin transaction");
        delegateTxManager.begin();

        EntityManager em = null;
        em = emf.createEntityManager();
        currentEntityManager.set(em);
    }

    @Override
    public void begin(boolean readOnly) throws NotSupportedException, SystemException {
        if (readOnly) {
            logger.warn("OgmBackEnd does not enforce read-only transactions. Starting as normal transaction");
        }
        begin();
    }

    @Override
    public void commit() throws RollbackException, HeuristicMixedException,
                                HeuristicRollbackException, SystemException {
        logger.info("Commit transaction");

        EntityManager em = currentEntityManager.get();
        em.flush();
        em.close();

        delegateTxManager.commit();

        currentEntityManager.set(null);
    }

    @Override
    public Transaction getTransaction() throws SystemException {
        return delegateTxManager.getTransaction();
    }

    @Override
    public void rollback() throws SystemException {
        logger.info("Rollback transaction");
        delegateTxManager.rollback();

        currentEntityManager.set(null);
    }

    @Override
    public <T> T withTransaction(Callable<T> command) throws Exception {
        return withTransaction(command, null);
    }

    /**
     * For now, it ignores the value of the atomic parameter.
     */
    @Override
    public <T> T withTransaction(Callable<T> command, Atomic atomic) throws Exception {
        T result = null;
        boolean txFinished = false;
        while (!txFinished) {
            try {
                boolean inTopLevelTransaction = false;
                // the purpose of this test is to enable reuse of the existing transaction
                if (getTransaction() == null) {
                    logger.info("No previous transaction.  Will begin a new one.");
                    begin();
                    inTopLevelTransaction = true;
                } else {
                    logger.info("Already inside a transaction. Not nesting.");
                }
                // do some work
                result = command.call();
                if (inTopLevelTransaction) {
                    logger.info("Will commit a top-level transaction.");
                    commit();
                } else {
                    logger.info("Leaving an inner transaction.");
                }
                txFinished = true;
                return result;
            } catch (CacheException ce) {
                //If the execution fails
                logException(ce);
            } catch(RollbackException re) {
                //If the transaction was marked for rollback only, the transaction is rolled back and this exception is thrown.
                logException(re);
            } catch(HeuristicMixedException hme) {
                //If a heuristic decision was made and some some parts of the transaction have been committed while other parts have been rolled back.
                //Pedro -- most of the time, happens when some nodes fails...
                logException(hme);
            } catch(HeuristicRollbackException hre) {
                //If a heuristic decision to roll back the transaction was made
                logException(hre);
            } catch (Exception e) { // any other exception gets out
                logger.info("Exception within transaction", e);
                throw e;
            } finally {
                if (!txFinished) {
                    try {
                        rollback();
                    } catch(IllegalStateException ise) {
                        // If the transaction is in a state where it cannot be rolled back.
                        // Pedro -- happen when the commit fails. When commit fails, it invokes the rollback().
                        //          so rollback() will be invoked again, but the transaction no longer exists
                        // Pedro -- just ignore it
                    } catch (Exception ex) {
                        logger.error("Exception while aborting transaction");
                        ex.printStackTrace();
                    }
                }
            }
            // Pedro had this wait here.  Why?
            // waitingBeforeRetry();

            logger.info("Retrying transaction: " + command);
        }
        // never reached
        throw new RuntimeException("code never reached");
    }


    // private static final Random rand = new Random();
    // private void waitingBeforeRetry() {
    //     try {
    //         //smf: why so long?!
    //         Thread.sleep(rand.nextInt(10000));
    //     } catch(InterruptedException ie) {
    //         //do nothing
    //     }
    // }

    private void logException(Exception e) {
        logger.info("Exception caught in transaction: " + e.getLocalizedMessage());
        logger.info("Exception caught in transaction:", e);
    }

}
