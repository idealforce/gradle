/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.ivyservice;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import org.apache.ivy.Ivy;
import org.apache.ivy.core.module.descriptor.*;
import org.apache.ivy.core.module.id.ArtifactId;
import org.apache.ivy.core.module.id.ModuleId;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.resolve.IvyNode;
import org.apache.ivy.core.resolve.ResolveData;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.resolve.ResolvedModuleRevision;
import org.apache.ivy.plugins.latest.ArtifactInfo;
import org.apache.ivy.plugins.latest.LatestRevisionStrategy;
import org.apache.ivy.plugins.resolver.DependencyResolver;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.*;
import org.gradle.api.internal.artifacts.ArtifactDependencyResolver;
import org.gradle.api.internal.artifacts.DefaultResolvedDependency;
import org.gradle.api.internal.artifacts.ResolvedConfigurationIdentifier;
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.EnhancedDependencyDescriptor;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.IvyConfig;
import org.gradle.api.specs.Spec;
import org.gradle.util.WrapUtil;

import java.util.*;

public class DefaultDependencyResolver implements ArtifactDependencyResolver {
    private final ModuleDescriptorConverter moduleDescriptorConverter;
    private final ResolvedArtifactFactory resolvedArtifactFactory;
    private final ResolveIvyFactory ivyFactory;

    public DefaultDependencyResolver(ResolveIvyFactory ivyFactory, ModuleDescriptorConverter moduleDescriptorConverter, ResolvedArtifactFactory resolvedArtifactFactory) {
        this.ivyFactory = ivyFactory;
        this.moduleDescriptorConverter = moduleDescriptorConverter;
        this.resolvedArtifactFactory = resolvedArtifactFactory;
    }

    public ResolvedConfiguration resolve(ConfigurationInternal configuration) throws ResolveException {
        Ivy ivy = ivyFactory.create(configuration.getResolutionStrategy());

        IvyConfig ivyConfig = new IvyConfig(ivy.getSettings(), configuration.getResolutionStrategy());
        ModuleDescriptor moduleDescriptor = moduleDescriptorConverter.convert(configuration.getAll(), configuration.getModule(), ivyConfig);
        DependencyResolver resolver = ivy.getSettings().getDefaultResolver();
        ResolveOptions options = new ResolveOptions();
        options.setDownload(false);
        options.setConfs(WrapUtil.toArray(configuration.getName()));
        ResolveData resolveData = new ResolveData(ivy.getResolveEngine(), options);
        DependencyToModuleResolver dependencyResolver = new IvyResolverBackedDependencyToModuleResolver(ivy, resolveData, resolver);
        IvyResolverBackedArtifactToFileResolver artifactResolver = new IvyResolverBackedArtifactToFileResolver(resolver);
        ResolveState resolveState = new ResolveState();
        ConfigurationResolveState root = resolveState.getConfiguration(moduleDescriptor, configuration.getName());
        ResolvedConfigurationImpl result = new ResolvedConfigurationImpl(configuration, root.getResult());
        resolve(dependencyResolver, result, root, resolveState, resolveData, artifactResolver);

        System.out.println("-> RESULT");
        for (ResolvedArtifact artifact : result.getResolvedArtifacts()) {
            System.out.println("  " + artifact.getModule().getId() + " " + artifact.getName());
        }
        for (UnresolvedDependency dependency : result.getUnresolvedDependencies()) {
            System.out.println("  unresolved " + dependency.getId());
        }

        return result;
    }

    private void resolve(DependencyToModuleResolver resolver, ResolvedConfigurationImpl result, ConfigurationResolveState root, ResolveState resolveState, ResolveData resolveData, ArtifactToFileResolver artifactResolver) {
        System.out.println("-> RESOLVE " + root);

        SetMultimap<ModuleId, DependencyResolvePath> conflicts = LinkedHashMultimap.create();

        List<DependencyResolvePath> queue = new ArrayList<DependencyResolvePath>();
        root.addOutgoingDependencies(new RootPath(), queue);

        while (!queue.isEmpty() || !conflicts.isEmpty()) {
            if (queue.isEmpty()) {
                ModuleId moduleId = conflicts.keySet().iterator().next();
                Set<ModuleRevisionResolveState> candidates = resolveState.getRevisions(moduleId);
                System.out.println("selecting moduleId from conflicts " + candidates);
                List<ModuleResolveStateBackedArtifactInfo> artifactInfos = new ArrayList<ModuleResolveStateBackedArtifactInfo>();
                for (final ModuleRevisionResolveState moduleRevision : candidates) {
                    artifactInfos.add(new ModuleResolveStateBackedArtifactInfo(moduleRevision));
                }
                List<ModuleResolveStateBackedArtifactInfo> sorted = new LatestRevisionStrategy().sort(artifactInfos.toArray(new ArtifactInfo[artifactInfos.size()]));
                ModuleRevisionResolveState selected = sorted.get(sorted.size() - 1).moduleRevision;
                System.out.println("  selected " + selected);
                selected.status = Status.Include;
                for (ModuleRevisionResolveState candidate : candidates) {
                    if (candidate != selected) {
                        candidate.status = Status.Evict;
                        for (DependencyResolvePath path : candidate.incomingPaths) {
                            path.restart(selected, queue);
                        }
                    }
                }
                Set<DependencyResolvePath> paths = conflicts.removeAll(moduleId);
                for (DependencyResolvePath path : paths) {
                    path.restart(selected, queue);
                }
                continue;
            }

            DependencyResolvePath path = queue.remove(0);
            System.out.println("* path " + path);

            try {
                path.resolve(resolver, resolveState);
            } catch (Throwable t) {
                result.addUnresolvedDependency(path.dependency.descriptor, t);
                continue;
            }

            if (path.targetModuleRevision.status == Status.Conflict) {
                conflicts.put(path.targetModuleRevision.descriptor.getModuleRevisionId().getModuleId(), path);
            } else {
                path.addOutgoingDependencies(resolveData, resolveState, queue);
            }
        }
        
        for (ConfigurationResolveState resolvedConfiguration : resolveState.getConfigurations()) {
            resolvedConfiguration.attachToParents(resolvedArtifactFactory, artifactResolver, result);
        }
    }

    private static class ResolveState {
        final SetMultimap<ModuleId, ModuleRevisionResolveState> modules = LinkedHashMultimap.create();
        final Map<ModuleRevisionId, ModuleRevisionResolveState> revisions = new LinkedHashMap<ModuleRevisionId, ModuleRevisionResolveState>();
        final Map<ResolvedConfigurationIdentifier, ConfigurationResolveState> configurations = new LinkedHashMap<ResolvedConfigurationIdentifier, ConfigurationResolveState>();

        ModuleRevisionResolveState getRevision(ModuleDescriptor descriptor) {
            ModuleRevisionResolveState moduleRevision = revisions.get(descriptor.getModuleRevisionId());
            if (moduleRevision == null) {
                moduleRevision = new ModuleRevisionResolveState(descriptor);
                revisions.put(descriptor.getModuleRevisionId(), moduleRevision);
                ModuleId moduleId = descriptor.getModuleRevisionId().getModuleId();
                modules.put(moduleId, moduleRevision);
                Set<ModuleRevisionResolveState> revisionsForModule = modules.get(moduleId);
                if (revisionsForModule.size() > 1) {
                    System.out.println("-> conflicts " + revisionsForModule);
                    for (ModuleRevisionResolveState revision : revisionsForModule) {
                        revision.status = Status.Conflict;
                    }
                }
            }
            
            return moduleRevision;
        }

        ConfigurationResolveState getConfiguration(ModuleDescriptor descriptor, String configurationName) {
            ResolvedConfigurationIdentifier id = new ResolvedConfigurationIdentifier(descriptor.getModuleRevisionId(), configurationName);
            ConfigurationResolveState configuration = configurations.get(id);
            if (configuration == null) {
                ModuleRevisionResolveState moduleRevision = getRevision(descriptor);
                configuration = new ConfigurationResolveState(moduleRevision, descriptor, configurationName, this);
                configurations.put(id, configuration);
            }
            return configuration;
        }

        public Collection<ConfigurationResolveState> getConfigurations() {
            return configurations.values();
        }

        public Set<ModuleRevisionResolveState> getRevisions(ModuleId moduleId) {
            return modules.get(moduleId);
        }
    }

    enum Status { Include, Conflict, Evict }

    private static class ModuleRevisionResolveState {
        final ModuleDescriptor descriptor;
        Status status = Status.Include;
        final Set<DependencyResolvePath> incomingPaths = new LinkedHashSet<DependencyResolvePath>();
        Set<DependencyResolveState> dependencies;

        private ModuleRevisionResolveState(ModuleDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public String toString() {
            return descriptor.getModuleRevisionId().toString();
        }

        public Status getStatus() {
            return status;
        }

        public Set<DependencyResolveState> getDependencies() {
            if (dependencies == null) {
                dependencies = new LinkedHashSet<DependencyResolveState>();
                for (DependencyDescriptor dependencyDescriptor : descriptor.getDependencies()) {
                    dependencies.add(new DependencyResolveState(dependencyDescriptor));
                }
            }
            return dependencies;
        }

        public void addIncomingPaths(DependencyResolvePath path) {
            incomingPaths.add(path);
        }

        public String getId() {
            ModuleRevisionId id = descriptor.getModuleRevisionId();
            return String.format("%s:%s:%s", id.getOrganisation(), id.getName(), id.getRevision());
        }
    }

    private static class ConfigurationResolveState {
        final ModuleRevisionResolveState moduleRevision;
        final ModuleDescriptor descriptor;
        final String configurationName;
        final Set<String> heirarchy = new LinkedHashSet<String>();
        final Set<ResolvePath> incomingPaths = new LinkedHashSet<ResolvePath>();
        DefaultResolvedDependency result;
        Set<ResolvedArtifact> artifacts;

        private ConfigurationResolveState(ModuleRevisionResolveState moduleRevision, ModuleDescriptor descriptor, String configurationName, ResolveState container) {
            this.moduleRevision = moduleRevision;
            this.descriptor = descriptor;
            this.configurationName = configurationName;
            findAncestors(configurationName, container, heirarchy);
        }

        Status getStatus() {
            return moduleRevision.getStatus();
        }

        void findAncestors(String config, ResolveState container, Set<String> ancestors) {
            ancestors.add(config);
            for (String parentConfig : descriptor.getConfiguration(config).getExtends()) {
                ancestors.addAll(container.getConfiguration(descriptor, parentConfig).heirarchy);
            }
        }

        void addIncomingPath(DependencyResolvePath path) {
            incomingPaths.add(path);
        }

        void addOutgoingDependencies(ResolvePath incomingPath, Collection<DependencyResolvePath> dependencies) {
            if (incomingPath.canReach(this)) {
                System.out.println("    skipping " + incomingPath + " as it already traverses " + this);
                return;
            }
            for (DependencyResolveState dependency : moduleRevision.getDependencies()) {
                Set<String> targetConfigurations = dependency.getTargetConfigurations(this);
                if (!targetConfigurations.isEmpty()) {
                    DependencyResolvePath dependencyResolvePath = new DependencyResolvePath(incomingPath, this, dependency, targetConfigurations);
                    dependencies.add(dependencyResolvePath);
                }
            }
        }

        @Override
        public String toString() {
            return String.format("%s(%s)", descriptor.getModuleRevisionId(), configurationName);
        }

        public Set<ResolvedArtifact> getArtifacts(ResolvedArtifactFactory resolvedArtifactFactory, ArtifactToFileResolver resolver) {
            if (artifacts == null) {
                artifacts = new LinkedHashSet<ResolvedArtifact>();
                for (String config : heirarchy) {
                    for (Artifact artifact : descriptor.getArtifacts(config)) {
                        artifacts.add(resolvedArtifactFactory.create(getResult(), artifact, resolver));
                    }
                }
            }
            return artifacts;
        }

        public DefaultResolvedDependency getResult() {
            if (result == null) {
                result = new DefaultResolvedDependency(
                        descriptor.getModuleRevisionId().getOrganisation(),
                        descriptor.getModuleRevisionId().getName(),
                        descriptor.getModuleRevisionId().getRevision(),
                        configurationName);
            }

            return result;
        }

        public void attachToParents(ResolvedArtifactFactory resolvedArtifactFactory, ArtifactToFileResolver resolver, ResolvedConfigurationImpl result) {
            switch (getStatus()) {
                case Include:
                    System.out.println("Attaching " + this + " to parents");
                    for (ResolvePath incomingPath : incomingPaths) {
                        incomingPath.attachToParents(this, resolvedArtifactFactory, resolver, result);
                    }
                    break;
                case Evict:
                    System.out.println("Ignoring evicted configuration " + this);
                    break;
                default:
                    throw new IllegalStateException(String.format("Unexpected state %s for %s at end of resolution.", getStatus(), this));
            }
        }
    }

    private static abstract class ResolvePath {
        public abstract void attachToParents(ConfigurationResolveState childConfiguration, ResolvedArtifactFactory resolvedArtifactFactory, ArtifactToFileResolver resolver, ResolvedConfigurationImpl result);

        public abstract boolean excludes(ModuleRevisionResolveState moduleRevision);

        public abstract boolean canReach(ConfigurationResolveState configuration);

        public abstract void addPathAsModules(Collection<ModuleRevisionResolveState> modules);
    }
    
    private static class RootPath extends ResolvePath {
        @Override
        public String toString() {
            return "<root>";
        }

        @Override
        public boolean excludes(ModuleRevisionResolveState moduleRevision) {
            return false;
        }

        @Override
        public boolean canReach(ConfigurationResolveState configuration) {
            return false;
        }

        @Override
        public void addPathAsModules(Collection<ModuleRevisionResolveState> modules) {
        }

        @Override
        public void attachToParents(ConfigurationResolveState childConfiguration, ResolvedArtifactFactory resolvedArtifactFactory, ArtifactToFileResolver resolver, ResolvedConfigurationImpl result) {
            // Don't need to do anything
        }
    }

    private static class DependencyResolveState {
        final DependencyDescriptor descriptor;
        ModuleRevisionResolveState targetModuleRevision;
        ResolvedModuleRevision resolvedRevision;

        private DependencyResolveState(DependencyDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public String toString() {
            return descriptor.toString();
        }

        public void resolve(DependencyToModuleResolver resolver, ResolveState resolveState) {
            if (resolvedRevision == null) {
                resolvedRevision = resolver.resolve(descriptor);
                targetModuleRevision = resolveState.getRevision(resolvedRevision.getDescriptor());
                System.out.println("  " + this + " resolved to " + targetModuleRevision);
            }
        }

        public Set<String> getTargetConfigurations(ConfigurationResolveState fromConfiguration) {
            Set<String> targetConfigurations = new LinkedHashSet<String>();
            for (String moduleConfiguration : descriptor.getModuleConfigurations()) {
                if (moduleConfiguration.equals("*") || fromConfiguration.heirarchy.contains(moduleConfiguration)) {
                    for (String targetConfiguration : descriptor.getDependencyConfigurations(moduleConfiguration)) {
                        if (targetConfiguration.equals("*")) {
                            Collections.addAll(targetConfigurations, fromConfiguration.descriptor.getPublicConfigurationsNames());
                        } else {
                            targetConfigurations.add(targetConfiguration);
                        }
                    }
                }
            }
            return targetConfigurations;
        }

        public String getDependencyId() {
            ModuleRevisionId depId = descriptor.getDependencyRevisionId();
            return String.format("%s:%s:%s", depId.getOrganisation(), depId.getName(), depId.getRevision());
        }
    }

    private static class DependencyResolvePath extends ResolvePath {
        final ResolvePath path;
        final ConfigurationResolveState from;
        final Set<String> targetConfigurations;
        final DependencyResolveState dependency;
        ModuleRevisionResolveState targetModuleRevision;

        private DependencyResolvePath(ResolvePath path, ConfigurationResolveState from, DependencyResolveState dependency, Set<String> targetConfigurations) {
            this.path = path;
            this.from = from;
            this.dependency = dependency;
            this.targetConfigurations = targetConfigurations;
        }

        @Override
        public String toString() {
            return String.format("%s | %s -> %s(%s)", path, from, dependency.descriptor.getDependencyRevisionId(), targetConfigurations);
        }

        public void resolve(DependencyToModuleResolver resolver, ResolveState resolveState) {
            if (targetModuleRevision == null) {
                try {
                    dependency.resolve(resolver, resolveState);
                } catch (ModuleNotFoundException e) {
                    Formatter formatter = new Formatter();
                    formatter.format("Module %s not found. It is required by:", dependency.getDependencyId());
                    Set<ModuleRevisionResolveState> modules = new LinkedHashSet<ModuleRevisionResolveState>();
                    addPathAsModules(modules);
                    for (ModuleRevisionResolveState module : modules) {
                        formatter.format("%n    %s", module.getId());
                    }
                    throw new ModuleNotFoundException(formatter.toString(), e);
                }
                
                targetModuleRevision = dependency.targetModuleRevision;
            } // Else, we've been restarted
        }

        public void addOutgoingDependencies(ResolveData resolveData, ResolveState resolveState, Collection<DependencyResolvePath> queue) {
            if (excludes(targetModuleRevision)) {
                return;
            }

            targetModuleRevision.addIncomingPaths(this);

            ModuleDescriptor targetDescriptor = targetModuleRevision.descriptor;

            IvyNode node = new IvyNode(resolveData, targetDescriptor);
            Set<String> targets = new LinkedHashSet<String>();
            for (String targetConfiguration : targetConfigurations) {
                Collections.addAll(targets, node.getRealConfs(targetConfiguration));
            }

            for (String targetConfigurationName : targets) {
                ConfigurationResolveState targetConfiguration = resolveState.getConfiguration(targetDescriptor, targetConfigurationName);
                System.out.println("    refers to config " + targetConfiguration);
                targetConfiguration.addIncomingPath(this);
                if (dependency.descriptor.isTransitive()) {
                    targetConfiguration.addOutgoingDependencies(this, queue);
                }
            }
        }

        @Override
        public boolean excludes(ModuleRevisionResolveState moduleRevision) {
            String[] configurations = from.heirarchy.toArray(new String[from.heirarchy.size()]);
            boolean excluded = dependency.descriptor.doesExclude(configurations, new ArtifactId(moduleRevision.descriptor.getModuleRevisionId().getModuleId(), "ivy", "ivy", "ivy"));
            if (excluded) {
                System.out.println("   excluded by " + this);
                return true;
            }
            return path.excludes(moduleRevision);
        }

        @Override
        public void addPathAsModules(Collection<ModuleRevisionResolveState> modules) {
            modules.add(from.moduleRevision);
            path.addPathAsModules(modules);
        }                
        
        @Override
        public boolean canReach(ConfigurationResolveState configuration) {
            return from.equals(configuration) || path.canReach(configuration);
        }

        public void restart(ModuleRevisionResolveState moduleRevision, List<DependencyResolvePath> queue) {
            assert targetModuleRevision != null;
            targetModuleRevision = moduleRevision;
            System.out.println("    restart " + this + " with " + moduleRevision);
            queue.add(this);
        }

        private Set<ResolvedArtifact> getArtifacts(ConfigurationResolveState childConfiguration, ResolvedArtifactFactory resolvedArtifactFactory, ArtifactToFileResolver resolver) {
            String[] targetConfigurations = from.heirarchy.toArray(new String[from.heirarchy.size()]);
            DependencyArtifactDescriptor[] dependencyArtifacts = dependency.descriptor.getDependencyArtifacts(targetConfigurations);
            if (dependencyArtifacts.length == 0) {
                return Collections.emptySet();
            }
            Set<ResolvedArtifact> artifacts = new LinkedHashSet<ResolvedArtifact>();
            for (DependencyArtifactDescriptor artifactDescriptor : dependencyArtifacts) {
                MDArtifact artifact = new MDArtifact(childConfiguration.descriptor, artifactDescriptor.getName(), artifactDescriptor.getType(), artifactDescriptor.getExt(), artifactDescriptor.getUrl(), artifactDescriptor.getQualifiedExtraAttributes());
                artifacts.add(resolvedArtifactFactory.create(childConfiguration.getResult(), artifact, resolver));
            }
            return artifacts;
        }

        @Override
        public void attachToParents(ConfigurationResolveState childConfiguration, ResolvedArtifactFactory resolvedArtifactFactory, ArtifactToFileResolver resolver, ResolvedConfigurationImpl result) {
            System.out.println("  attach via " + this);
            System.out.println("    " + from + " -> " + childConfiguration);
            DefaultResolvedDependency parent = from.getResult();
            DefaultResolvedDependency child = childConfiguration.getResult();
            parent.addChild(child);

            Set<ResolvedArtifact> artifacts = getArtifacts(childConfiguration, resolvedArtifactFactory, resolver);
            if (!artifacts.isEmpty()) {
                child.addParentSpecificArtifacts(parent, artifacts);
            }
            
            boolean includeDefaults = dependency.descriptor instanceof EnhancedDependencyDescriptor && ((EnhancedDependencyDescriptor) dependency.descriptor).isIncludeDefaultArtifacts();
            if (artifacts.isEmpty() || includeDefaults) {
                child.addParentSpecificArtifacts(parent, childConfiguration.getArtifacts(resolvedArtifactFactory, resolver));
            }
            for (ResolvedArtifact artifact : child.getParentArtifacts(parent)) {
                result.addArtifact(artifact);
            }

            if (parent == result.getRoot()) {
                EnhancedDependencyDescriptor enhancedDependencyDescriptor = (EnhancedDependencyDescriptor) dependency.descriptor;
                result.addFirstLevelDependency(enhancedDependencyDescriptor.getModuleDependency(), child);
            }
        }
    }

    private static class ResolvedConfigurationImpl extends AbstractResolvedConfiguration {
        private final ResolvedDependency root;
        private final Configuration configuration;
        private final Map<ModuleDependency, ResolvedDependency> firstLevelDependencies = new LinkedHashMap<ModuleDependency, ResolvedDependency>();
        private final Set<ResolvedArtifact> artifacts = new LinkedHashSet<ResolvedArtifact>();
        private final Set<UnresolvedDependency> unresolvedDependencies = new LinkedHashSet<UnresolvedDependency>();

        private ResolvedConfigurationImpl(Configuration configuration, ResolvedDependency root) {
            this.configuration = configuration;
            this.root = root;
        }

        public boolean hasError() {
            return !unresolvedDependencies.isEmpty();
        }

        public void rethrowFailure() throws ResolveException {
            if (!unresolvedDependencies.isEmpty()) {
                List<Throwable> failures = new ArrayList<Throwable>();
                for (UnresolvedDependency unresolvedDependency : unresolvedDependencies) {
                    failures.add(unresolvedDependency.getProblem());
                }
                throw new ResolveException(configuration, Collections.<String>emptyList(), failures);
            }
        }

        @Override
        Set<UnresolvedDependency> getUnresolvedDependencies() {
            return unresolvedDependencies;
        }

        @Override
        Set<ResolvedDependency> doGetFirstLevelModuleDependencies(Spec<? super Dependency> dependencySpec) {
            Set<ResolvedDependency> matches = new LinkedHashSet<ResolvedDependency>();
            for (Map.Entry<ModuleDependency, ResolvedDependency> entry : firstLevelDependencies.entrySet()) {
                if (dependencySpec.isSatisfiedBy(entry.getKey())) {
                    matches.add(entry.getValue());
                }
            }
            return matches;
        }

        @Override
        protected ResolvedDependency getRoot() {
            return root;
        }

        public Set<ResolvedArtifact> getResolvedArtifacts() throws ResolveException {
            return artifacts;
        }

        public void addFirstLevelDependency(ModuleDependency moduleDependency, ResolvedDependency refersTo) {
            firstLevelDependencies.put(moduleDependency, refersTo);
        }

        public void addArtifact(ResolvedArtifact artifact) {
            artifacts.add(artifact);
        }

        public void addUnresolvedDependency(final DependencyDescriptor descriptor, final Throwable failure) {
            unresolvedDependencies.add(new DefaultUnresolvedDependency(descriptor.getDependencyRevisionId().toString(), configuration, failure));
        }
    }

    private static class ModuleResolveStateBackedArtifactInfo implements ArtifactInfo {
        final ModuleRevisionResolveState moduleRevision;

        public ModuleResolveStateBackedArtifactInfo(ModuleRevisionResolveState moduleRevision) {
            this.moduleRevision = moduleRevision;
        }

        public String getRevision() {
            return moduleRevision.descriptor.getRevision();
        }

        public long getLastModified() {
            throw new UnsupportedOperationException();
        }
    }
}
