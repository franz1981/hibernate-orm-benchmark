package org.hibernate.benchmark.enhancement;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.bytecode.enhance.spi.EnhancementContext;
import org.hibernate.bytecode.enhance.spi.Enhancer;
import org.hibernate.bytecode.internal.none.BytecodeProviderImpl;
import org.hibernate.bytecode.spi.BasicProxyFactory;
import org.hibernate.bytecode.spi.BytecodeProvider;
import org.hibernate.bytecode.spi.ProxyFactoryFactory;
import org.hibernate.bytecode.spi.ReflectionOptimizer;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.metamodel.internal.EntityInstantiatorPojoOptimized;
import org.hibernate.metamodel.internal.EntityInstantiatorPojoStandard;
import org.hibernate.metamodel.spi.EntityInstantiator;
import org.hibernate.property.access.spi.PropertyAccess;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.ProxyFactory;
import org.hibernate.type.CompositeType;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * @author Marco Belladelli
 */
@State( Scope.Thread )
public class EntityInstantiators {
	private static final int FORTUNES = 100;

	private SessionFactory standardSessionFactory;
	private Session standardSession;

	private SessionFactory optimizedSessionFactory;
	private Session optimizedSession;

	@Setup
	public void setup() {
		standardSessionFactory = getSessionFactory( new BytecodeProviderImpl(), EntityInstantiatorPojoStandard.class );
		optimizedSessionFactory = getSessionFactory(
				new FortuneBytecodeProvider(),
				EntityInstantiatorPojoOptimized.class
		);

		populateData( standardSessionFactory );

		standardSession = standardSessionFactory.openSession();
		standardSession.getTransaction().begin();
		optimizedSession = optimizedSessionFactory.openSession();
		optimizedSession.getTransaction().begin();
	}

	private void populateData(SessionFactory sf) {
		final Session session = sf.openSession();
		session.getTransaction().begin();
		for ( int i = 0; i < FORTUNES; i++ ) {
			session.persist( new Fortune( i ) );
		}
		session.getTransaction().commit();
		session.close();
	}

	protected SessionFactory getSessionFactory(BytecodeProvider bytecodeProvider, Class<?> expectedInstantiatorClass) {
		final Configuration config = new Configuration().addAnnotatedClass( Fortune.class );
		final StandardServiceRegistryBuilder srb = config.getStandardServiceRegistryBuilder();
		srb.applySetting( AvailableSettings.SHOW_SQL, false )
				.applySetting( AvailableSettings.LOG_SESSION_METRICS, false )
				.applySetting( AvailableSettings.DIALECT, "org.hibernate.dialect.H2Dialect" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_DRIVER, "org.h2.Driver" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_USER, "sa" )
				.applySetting( AvailableSettings.HBM2DDL_AUTO, "create-drop" );
		// force no runtime bytecode-enhancement
		srb.addService( BytecodeProvider.class, bytecodeProvider );
		final SessionFactoryImplementor sf = (SessionFactoryImplementor) config.buildSessionFactory( srb.build() );

		final EntityInstantiator instantiator = sf.getMappingMetamodel()
				.getEntityDescriptor( Fortune.class )
				.getRepresentationStrategy()
				.getInstantiator();
		if ( !expectedInstantiatorClass.isInstance( instantiator ) ) {
			throw new IllegalStateException( "The entity instantiator is not a EntityInstantiatorPojoStandard" );
		}

		return sf;
	}

	@TearDown
	public void destroy() {
		standardSession.getTransaction().commit();
		standardSession.close();
		standardSessionFactory.close();

		optimizedSession.getTransaction().commit();
		optimizedSession.close();
		optimizedSessionFactory.close();
	}

	@State( Scope.Thread )
	@AuxCounters( AuxCounters.Type.OPERATIONS )
	public static class EventCounters {
		public long instances;
	}

	@Benchmark
	public void standard(Blackhole bh, EventCounters counters) {
		queryFortune( standardSession, bh, counters );
	}

	@Benchmark
	public void optimized(Blackhole bh, EventCounters counters) {
		queryFortune( optimizedSession, bh, counters );
	}

	protected void queryFortune(Session session, Blackhole bh, EventCounters counters) {
		final List<Fortune> results = session.createQuery( "from Fortune", Fortune.class ).getResultList();
		for ( Fortune fortune : results ) {
			if ( bh != null ) {
				bh.consume( fortune );
			}
		}
		if ( counters != null ) {
			counters.instances += results.size();
		}
		session.clear();
	}

	@Entity( name = "Fortune" )
	static class Fortune {
		@Id
		public Integer id;

		public Fortune() {
		}

		public Fortune(Integer id) {
			this.id = id;
		}
	}

	public static void main(String[] args) {
		EntityInstantiators jpaBenchmark = new EntityInstantiators();
		jpaBenchmark.setup();

		for ( int i = 0; i < 1; i++ ) {
			jpaBenchmark.standard( null, null );
		}

		for ( int i = 0; i < 1; i++ ) {
			jpaBenchmark.optimized( null, null );
		}

		jpaBenchmark.destroy();
	}

	/**
	 * Empty {@link BytecodeProvider} which only provides the {@link FortuneInstantiator} static optimizer
	 */
	static class FortuneBytecodeProvider implements BytecodeProvider {
		@Override
		public ProxyFactoryFactory getProxyFactoryFactory() {
			return new NoProxyFactoryFactory();
		}

		@SuppressWarnings( "removal" )
		@Override
		public ReflectionOptimizer getReflectionOptimizer(
				Class clazz,
				String[] getterNames,
				String[] setterNames,
				Class[] types) {
			return null;
		}

		@Override
		public ReflectionOptimizer getReflectionOptimizer(
				Class<?> clazz,
				Map<String, PropertyAccess> propertyAccessMap) {
			assert clazz == Fortune.class;
			return new ReflectionOptimizer() {
				@Override
				public InstantiationOptimizer getInstantiationOptimizer() {
					return new FortuneInstantiator();
				}

				@Override
				public AccessOptimizer getAccessOptimizer() {
					return null;
				}
			};
		}

		@Override
		public Enhancer getEnhancer(EnhancementContext enhancementContext) {
			return null;
		}
	}

	/**
	 * Implementation of {@link ReflectionOptimizer.InstantiationOptimizer}
	 * which reflects the generated bytecode in both Hibernate and Quarkus
	 */
	static class FortuneInstantiator implements ReflectionOptimizer.InstantiationOptimizer {
		@Override
		public Object newInstance() {
			return new Fortune();
		}
	}

	static class NoProxyFactoryFactory implements ProxyFactoryFactory {
		@Override
		public ProxyFactory buildProxyFactory(SessionFactoryImplementor sessionFactory) {
			return new ProxyFactory() {
				@Override
				public void postInstantiate(
						String entityName,
						Class<?> persistentClass,
						Set<Class<?>> interfaces,
						Method getIdentifierMethod,
						Method setIdentifierMethod,
						CompositeType componentIdType) throws HibernateException {
				}

				@Override
				public HibernateProxy getProxy(Object id, SharedSessionContractImplementor session)
						throws HibernateException {
					throw new UnsupportedOperationException();
				}
			};
		}

		@Override
		public BasicProxyFactory buildBasicProxyFactory(Class superClassOrInterface) {
			return new BasicProxyFactory() {
				@Override
				public Object getProxy() {
					throw new UnsupportedOperationException();
				}
			};
		}
	}
}
