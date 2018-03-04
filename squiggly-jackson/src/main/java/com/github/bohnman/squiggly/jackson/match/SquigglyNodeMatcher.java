package com.github.bohnman.squiggly.jackson.match;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import com.github.bohnman.core.cache.CoreCache;
import com.github.bohnman.core.cache.CoreCacheBuilder;
import com.github.bohnman.core.tuple.CorePair;
import com.github.bohnman.squiggly.core.context.SquigglyContext;
import com.github.bohnman.squiggly.core.metric.source.CoreCacheSquigglyMetricsSource;
import com.github.bohnman.squiggly.core.name.AnyDeepName;
import com.github.bohnman.squiggly.core.name.ExactName;
import com.github.bohnman.squiggly.core.parser.ParseContext;
import com.github.bohnman.squiggly.core.parser.SquigglyNode;
import com.github.bohnman.squiggly.core.view.PropertyView;
import com.github.bohnman.squiggly.jackson.Squiggly;
import com.github.bohnman.squiggly.jackson.bean.BeanInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.bohnman.core.lang.CoreAssert.notNull;

public class SquigglyNodeMatcher {

    public static final SquigglyNode NEVER_MATCH = new SquigglyNode(new ParseContext(1, 1), AnyDeepName.get(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false, false, false);
    public static final SquigglyNode ALWAYS_MATCH = new SquigglyNode(new ParseContext(1, 1), AnyDeepName.get(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false, false, false);
    private static final List<SquigglyNode> BASE_VIEW_NODES = Collections.singletonList(new SquigglyNode(new ParseContext(1, 1), new ExactName(PropertyView.BASE_VIEW), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false, true, false));

    private final CoreCache<CorePair<Path, String>, SquigglyNode> matchCache;
    private final Squiggly squiggly;


    @SuppressWarnings("unchecked")
    public SquigglyNodeMatcher(Squiggly squiggly) {
        this.squiggly = notNull(squiggly);
        this.matchCache = CoreCacheBuilder.from(squiggly.getConfig().getFilterPathCacheSpec()).build();
        squiggly.getMetrics().add(new CoreCacheSquigglyMetricsSource("squiggly.filter.pathCache.", matchCache));
    }

    @SuppressWarnings("unchecked")
    public SquigglyNode match(final PropertyWriter writer, final JsonGenerator jgen) {
        if (!squiggly.getContextProvider().isFilteringEnabled()) {
            return ALWAYS_MATCH;
        }

        JsonStreamContext streamContext = getStreamContext(jgen);

        if (streamContext == null) {
            return ALWAYS_MATCH;
        }

        Path path = getPath(writer, streamContext);
        SquigglyContext context = squiggly.getContextProvider().getContext(path.getFirst().getBeanClass(), squiggly);
        String filter = context.getFilter();


        if (AnyDeepName.ID.equals(filter)) {
            return ALWAYS_MATCH;
        }

        if (path.isCachable()) {
            // cache the match result using the path and filter expression
            CorePair<Path, String> pair = CorePair.of(path, filter);
            SquigglyNode match = matchCache.get(pair);

            if (match == null) {
                match = matchPath(path, context);
            }

            matchCache.put(pair, match);
            return match;
        }

        return matchPath(path, context);
    }

    // perform the actual matching
    private SquigglyNode matchPath(Path path, SquigglyContext context) {
        List<SquigglyNode> nodes = context.getNode().getChildren();
        Set<String> viewStack = null;
        SquigglyNode viewNode = null;
        SquigglyNode match = null;

        int pathSize = path.getElements().size();
        int lastIdx = pathSize - 1;

        for (int i = 0; i < pathSize; i++) {
            PathElement element = path.getElements().get(i);

            if (viewNode != null && !viewNode.isSquiggly()) {
                Class beanClass = element.getBeanClass();

                if (beanClass != null && !Map.class.isAssignableFrom(beanClass)) {
                    Set<String> propertyNames = getPropertyNamesFromViewStack(element, viewStack);

                    if (!propertyNames.contains(element.getName())) {
                        return NEVER_MATCH;
                    }
                }

            } else if (nodes.isEmpty()) {
                return NEVER_MATCH;
            } else {
                match = findBestSimpleNode(element, nodes);

                if (match == null) {
                    match = findBestViewNode(element, nodes);

                    if (match != null) {
                        viewNode = match;
                        viewStack = addToViewStack(viewStack, viewNode);
                    }
                } else if (match.isAnyShallow()) {
                    viewNode = match;
                } else if (match.isAnyDeep()) {
                    return match;
                }

                if (match == null) {
                    if (isJsonUnwrapped(element)) {
                        match = ALWAYS_MATCH;
                        continue;
                    }

                    return NEVER_MATCH;
                }

                if (match.isNegated()) {
                    return NEVER_MATCH;
                }

                nodes = match.getChildren();

                if (i < lastIdx && nodes.isEmpty() && !match.isEmptyNested() && squiggly.getConfig().isFilterImplicitlyIncludeBaseFields()) {
                    nodes = BASE_VIEW_NODES;
                }
            }
        }

        if (match == null) {
            match = NEVER_MATCH;
        }

        return match;
    }


    // create a path structure representing the object graph
    private Path getPath(PropertyWriter writer, JsonStreamContext sc) {
        LinkedList<PathElement> elements = new LinkedList<>();

        if (sc != null) {
            elements.add(new PathElement(writer.getName(), sc.getCurrentValue()));
            sc = sc.getParent();
        }

        while (sc != null) {
            if (sc.getCurrentName() != null && sc.getCurrentValue() != null) {
                elements.addFirst(new PathElement(sc.getCurrentName(), sc.getCurrentValue()));
            }
            sc = sc.getParent();
        }

        return new Path(elements);
    }

    private JsonStreamContext getStreamContext(JsonGenerator jgen) {
        return jgen.getOutputContext();
    }


    private boolean isJsonUnwrapped(PathElement element) {
        BeanInfo info = squiggly.getBeanInfoIntrospector().introspect(element.getBeanClass());
        return info.isUnwrapped(element.getName());
    }

    private Set<String> getPropertyNamesFromViewStack(PathElement element, Set<String> viewStack) {
        if (viewStack == null) {
            return getPropertyNames(element, PropertyView.BASE_VIEW);
        }

        Set<String> propertyNames = new HashSet<>();

        for (String viewName : viewStack) {
            Set<String> names = getPropertyNames(element, viewName);

            if (names.isEmpty() && squiggly.getConfig().isFilterImplicitlyIncludeBaseFields()) {
                names = getPropertyNames(element, PropertyView.BASE_VIEW);
            }

            propertyNames.addAll(names);
        }

        return propertyNames;
    }

    private SquigglyNode findBestViewNode(PathElement element, List<SquigglyNode> nodes) {
        if (Map.class.isAssignableFrom(element.getBeanClass())) {
            for (SquigglyNode node : nodes) {
                if (PropertyView.BASE_VIEW.equals(node.getName())) {
                    return node;
                }
            }
        } else {
            for (SquigglyNode node : nodes) {
                // handle view
                Set<String> propertyNames = getPropertyNames(element, node.getName());

                if (propertyNames.contains(element.getName())) {
                    return node;
                }
            }
        }

        return null;
    }

    private SquigglyNode findBestSimpleNode(PathElement element, List<SquigglyNode> nodes) {
        SquigglyNode match = null;
        int lastMatchStrength = -1;

        for (SquigglyNode node : nodes) {
            int matchStrength = node.match(element.getName());

            if (matchStrength < 0) {
                continue;
            }

            if (lastMatchStrength < 0 || matchStrength >= lastMatchStrength) {
                match = node;
                lastMatchStrength = matchStrength;
            }

        }

        return match;
    }

    private Set<String> addToViewStack(Set<String> viewStack, SquigglyNode viewNode) {
        if (!squiggly.getConfig().isFilterPropagateViewToNestedFilters()) {
            return null;
        }

        if (viewStack == null) {
            viewStack = new HashSet<>();
        }

        viewStack.add(viewNode.getName());

        return viewStack;
    }

    private Set<String> getPropertyNames(PathElement element, String viewName) {
        Class beanClass = element.getBeanClass();

        if (beanClass == null) {
            return Collections.emptySet();
        }

        return squiggly.getBeanInfoIntrospector().introspect(beanClass).getPropertyNamesForView(viewName);
    }

    /*
        Represents the path structuore in the object graph
     */
    private static class Path {

        private final String id;
        private final LinkedList<PathElement> elements;

        public Path(LinkedList<PathElement> elements) {
            StringBuilder idBuilder = new StringBuilder();

            for (int i = 0; i < elements.size(); i++) {
                PathElement element = elements.get(i);

                if (i > 0) {
                    idBuilder.append('.');
                }

                idBuilder.append(element.getName());
            }

            id = idBuilder.toString();
            this.elements = elements;
        }

        public String getId() {
            return id;
        }

        public List<PathElement> getElements() {
            return elements;
        }

        public PathElement getFirst() {
            return elements.getFirst();
        }

        public PathElement getLast() {
            return elements.getLast();
        }

        // we use the last element because that is where the json stream context started
        public Class getBeanClass() {
            return getLast().getBeanClass();
        }

        // maps aren't cachable
        public boolean isCachable() {
            Class beanClass = getBeanClass();
            return beanClass != null && !Map.class.isAssignableFrom(beanClass);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Path path = (Path) o;
            Class beanClass = getBeanClass();
            Class oBeanClass = path.getBeanClass();

            if (!id.equals(path.id)) return false;
            if (beanClass != null ? !beanClass.equals(oBeanClass) : oBeanClass != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = id.hashCode();
            Class beanClass = getBeanClass();
            result = 31 * result + (beanClass != null ? beanClass.hashCode() : 0);
            return result;
        }
    }

    // represent a specific point in the path.
    private static class PathElement {
        private final String name;
        private final Class bean;

        public PathElement(String name, Object bean) {
            this.name = name;
            this.bean = bean.getClass();
        }

        public String getName() {
            return name;
        }

        public Class getBeanClass() {
            return bean;
        }
    }
}
