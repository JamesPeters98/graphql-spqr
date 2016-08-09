package io.leangen.graphql.metadata;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.leangen.graphql.annotations.GraphQLResolverSource;
import io.leangen.graphql.annotations.RelayConnectionRequest;
import io.leangen.graphql.generator.Executable;
import io.leangen.graphql.query.ConnectionRequest;
import io.leangen.graphql.query.ExecutionContext;

/**
 * Class representing a single method used to resolve a specific query given specific arguments.
 * A single query can have multiple resolvers, corresponding to different combinations of arguments.
 * This is done mainly to support attaching multiple overloaded methods as resolvers for the same query.
 * Two resolvers of the same query must not accept arguments if the same name.
 * @author bojan.tomic (kaqqao)
 */
public class QueryResolver {

	private Executable executable;
	private String queryName;
	private String queryDescription;
	private List<QueryArgument> queryArguments;
	private List<Parameter> connectionRequestArguments;
	private AnnotatedType returnType;
	private QueryArgument sourceArgument;
	private String wrappedAttribute;
	private Set<List<String>> parentQueryTrails;
	private boolean relayId;

	public QueryResolver(String queryName, String queryDescription, boolean relayId,
	                     Executable executable, List<QueryArgument> queryArguments) {
		this.executable = executable;
		this.queryName = queryName;
		this.queryDescription = queryDescription;
		this.relayId = relayId;
		this.queryArguments = queryArguments;
		this.connectionRequestArguments = resolveConnectionRequestArguments();
		this.returnType = executable.getReturnType();
		this.parentQueryTrails = executable.getParentTrails();
		this.wrappedAttribute = executable.getWrappedAttribute();
		this.sourceArgument = resolveSource(queryArguments);
	}

	/**
	 * Finds the argument representing the query source (object returned by the parent query), if it exists.
	 * Query source argument will (potentially) exist only for the resolvers of nestable queries.
	 * Even then, not all resolvers of such queries necessarily accept a source object.
	 * @param arguments All arguments that this resolver accepts
	 * @return The argument representing the query source (object returned by the parent query),
	 *  or null if this resolver doesn't accept a query source
	 */
	private QueryArgument resolveSource(List<QueryArgument> arguments) {
		Optional<QueryArgument> source = arguments.stream().filter(QueryArgument::isResolverSource).findFirst();
		return source.orElse(null);
	}

	/**
	 * Finds all method parameters used for Relay connection-style pagination. Should support arbitrary parameter mapping,
	 * but currently only parameters of type {@link ConnectionRequest} or those annotated with {@link RelayConnectionRequest}
	 * (but still with no mapping applied) are recognized.
	 * @return The parameters used for Relay connection-style pagination
	 */
	private List<Parameter> resolveConnectionRequestArguments() {
		List<Parameter> queryContextArguments = new ArrayList<>();
		for (int i = 0; i < executable.getParameterCount(); i++) {
			Parameter parameter = executable.getParameters()[i];
			if (isConnectionRequestArgument(parameter)) {
				queryContextArguments.add(parameter);
			}
		}
		return queryContextArguments;
	}

	private boolean isConnectionRequestArgument(Parameter parameter) {
		return parameter.isAnnotationPresent(RelayConnectionRequest.class) || ConnectionRequest.class.isAssignableFrom(parameter.getType());
	}

	/**
	 * Prepares the parameters by mapping/parsing the input and/or source object and invokes the underlying resolver method/field
	 * @param source The source object for this query (the result of the parent query)
	 * @param arguments All regular (non-Relay connection specific) arguments as provided in the query
	 * @param connectionRequest Relay connection specific arguments provided in the query
	 * @param executionContext An object containing all global information that might be needed during resolver execution
	 * @return The result returned by the underlying method/field, potentially proxied and wrapped
	 * @throws InvocationTargetException
	 * @throws IllegalAccessException
	 */
	public Object resolve(Object source, Map<String, Object> arguments, Object connectionRequest, ExecutionContext executionContext) throws InvocationTargetException, IllegalAccessException {
		int queryArgumentsCount = queryArguments.size();
		int sourceObjectPosition = getQuerySourceObjectPosition();
		int contextObjectPosition = getConnectionRequestObjectPosition();
		int idObjectPosition = getIdObjectPosition();
		if (contextObjectPosition > -1) {
			++queryArgumentsCount;
		}

		Object[] args = new Object[queryArgumentsCount];
		int currentParamIndex = 0;
		for (int i = 0; i < queryArgumentsCount; i++) {
			if (i == contextObjectPosition) {
				args[i] = connectionRequest;
			} else if (i == sourceObjectPosition && !arguments.containsKey(sourceArgument.getName())) {
				args[i] = source;
			} else if (i == idObjectPosition) {
				String rawId = arguments.get(queryArguments.get(currentParamIndex++).getName()).toString();
				String id = rawId;
				try {
					id = executionContext.relay.fromGlobalId(rawId).id;
				} catch (Exception e) {/*noop*/}
				args[i] = executionContext.idTypeMapper.deserialize(id, executable.getAnnotatedParameterTypes()[idObjectPosition].getType());
			} else {
				QueryArgument inputArgument = queryArguments.get(currentParamIndex++);
				args[i] = executionContext.inputDeserializer.deserialize(arguments.get(inputArgument.getName()), inputArgument.getJavaType().getType());
			}
		}
		Object result = executable.execute(source, args);
		if (result instanceof Collection) {
			result = new ArrayList<>(((Collection<?>) result));
		}
		//Wrap returned values for resolvers that don't directly return domain objects
		if (isWrapped()) {
			if (!Map.class.isAssignableFrom(result.getClass())) {
				Map<String, Object> wrappedResult = new HashMap<>(1);
				wrappedResult.put(wrappedAttribute, result);
				return wrappedResult;
			}
			return result;
		}
		return result;
	}

	public boolean supportsConnectionRequests() {
		return !connectionRequestArguments.isEmpty();
	}

	/**
	 * Returns whether this resolver is the primary. Primary resolver is the one accepting nothing but the Relay ID
	 * @return Boolean indicating whether this resolver is the primary resolver for this query
	 */
	public boolean isPrimaryResolver() {
		return queryArguments.size() == 1 && queryArguments.get(0).isRelayId();
	}

	/**
	 * Gets the generic Java type of the source object (object returned by the parent query),
	 * if one is accepted by this resolver. Used to decide if this query can be nested inside another.
	 * @return The generic Java type of the source object, or null if this resolver does not accept one.
	 */
	public Type getSourceType() {
		return sourceArgument == null ? null : sourceArgument.getJavaType().getType();
	}


	/**
	 * Gets the index of the argument representing the query source (object returned by the parent query),
	 * or -1 if this resolved does not accept such an object.
	 * @return The index of the argument representing the query source
	 */
	private int getQuerySourceObjectPosition() {
		for (int i = 0; i < executable.getParameterCount(); i++) {
			if (executable.getParameters()[i].isAnnotationPresent(GraphQLResolverSource.class)) {
				return i;
			}
		}
		return -1;
	}

	private int getConnectionRequestObjectPosition() {
		if (!supportsConnectionRequests()) return -1;
		for (int i = 0; i < executable.getParameterCount(); i++) {
			if (executable.getParameters()[i].getType().equals(ConnectionRequest.class)) {
				return i;
			}
		}
		return -1;
	}

	//TODO Deal with methods accepting multiple Relay IDs
	/**
	 * Returns the index of the argument representing Relay ID, as such object have special logic for (de)serialization
	 * @return The index of the argument representing Relay ID
	 */
	private int getIdObjectPosition() {
		for (int i = 0; i < queryArguments.size(); i++) {
			if (queryArguments.get(i).isRelayId()) {
				return i;
			}
		}
		return -1;
	}

	private boolean isWrapped() {
		return !(wrappedAttribute == null || wrappedAttribute.isEmpty());
	}

	public String getQueryName() {
		return queryName;
	}

	public String getQueryDescription() {
		return queryDescription;
	}

	public boolean isRelayId() {
		return relayId;
	}

	public Set<List<String>> getParentQueryTrails() {
		return parentQueryTrails;
	}

	/**
	 * Get the fingerprint of this resolver. Fingerprint uniquely identifies a resolver withing a query.
	 * It is based on the name of the query and all parameters this specific resolver accepts.
	 * It is used to decide which resolver to invoke for the query, based on the provided arguments.
	 * @param parentTrail The string representation of all
	 * @return
	 */
	public Set<String> getFingerprints(String parentTrail) {
		Set<String> fingerprints = new HashSet<>(sourceArgument == null ? 1 : 2);
		StringBuilder fingerPrint = new StringBuilder(parentTrail);
		queryArguments.stream().map(QueryArgument::getName).sorted().forEach(fingerPrint::append);
		fingerprints.add(fingerPrint.toString());
		if (sourceArgument != null) {
			fingerPrint = new StringBuilder(parentTrail);
			queryArguments.stream()
					.filter(arg -> !arg.isResolverSource())
					.map(QueryArgument::getName)
					.sorted()
					.forEach(fingerPrint::append);
			fingerprints.add(fingerPrint.toString());
		}
		return fingerprints;
	}

	public int getFingerprint() {
		return executable.hashCode();
	}

	public List<QueryArgument> getQueryArguments() {
		return queryArguments;
	}

	public AnnotatedType getReturnType() {
		return returnType;
	}

	@Override
	public String toString() {
		return executable.toString();
	}
}