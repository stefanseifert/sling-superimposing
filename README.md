Sling Superimposing Resource Provider
===================

### DEPRECATED

This GitHub Repository is **deprecated** because the code is now maintained within the Apache Sling Project:<br/>
https://svn.apache.org/repos/asf/sling/trunk/contrib/extensions/superimposing


### About

The Superimposing Resource Provider is an extension for the [Apache Sling](http://sling.apache.org/) framework. It implements the [ResourceProvider](http://sling.apache.org/apidocs/sling6/org/apache/sling/api/resource/ResourceProvider.html) interface.

Goals of the solution:

* Mirroring resource trees
 * Reflect changes from master tree
 * Avoid unnecessary copies
* Superimposing resources
 * Add
 * Remove
 * Overlay

There is a presentation from [adaptTo() 2013](http://adaptto.org) with more background information:<br/>
[Superimposing Content Presentation adaptTo() 2013](http://www.pro-vision.de/content/medialib/pro-vision/production/adaptto/2013/adaptto2013-lightning-superimposing-content-julian-sedding-stefa/_jcr_content/renditions/rendition.file/adaptto2013-lightning-superimposing-content-julian-sedding-stefan-seifert.pdf)

The implementation of this provider is based on the great work of Julian Sedding from [SLING-1778](https://issues.apache.org/jira/browse/SLING-1778).


### How to use

Preparations:

* Compile the OSGi Bundle (Recommended environment: Java 7 and Maven 3.0.5)
* Deploy the Bundle to your application (running on Apache Sling or AEM)
* By default the resource provider is _not_ active. You have to enable it via OSGi configuration in the Felix Console (see below)

To create a superimposed resource create a node in JCR with:

* Node type **sling:SuperimposeResource**
 * Alternatively you can create a node with any other node type and use the mixin **sling:Superimpose**
* Property **sling:superimposeSourcePath**: points to an absolute path - this content is mirrored to the location of the new node
* (Optional) Property **sling:superimposeRegisterParent**: If set to true, not the new node itself but its parent is used as root node for the superimposed content. This is useful if you have no control about the parent node itself (e.g. a cq:Page node in AEM).
* (Optional) Property **sling:superimposeOverlayable**: If set to true, the content is not only mirrored, but can be overlayed by nodes in the target tree below the superimposing root node. _Please note that this feature is still experimental._


### Configuration

In the Felix console you can configure the creation of Superimposing Resource Providers via the service "Apache Sling Superimposing Resource Manager":

* **enabled**: If set to true, the superimposing is active
* **findAllQueries**: Defines JCR queries that are executed on service startup to detect all superimposing nodes that are already created in the JCR. By default only the /content subtree is scanned.
* **obervationPaths**: Paths on which the new, updated or removed superimposing nodes are automatically detected on runtime.


### Remarks

* The superimposing resource provider depends on an underlying JCR repository. It currently does only work with JCR and supports mirroring or overlaying JCR nodes.
* The Superimposing Resource Provider provides an API in the package org.apache.sling.superimposing. For the basic superimposing content features you do not need this API. It is a read-only API which allows to query which superimposing resource providers are currently active with which configuration. The API is useful if you want to react on JCR events on the source tree and actions on the mirrored trees as well (e.g. for sending invalidation events to clean an external cache).
* If your are using AEM you should enable the superimposing resource provider **only on publish instances**. Use it on author instances on your own risk! It can produce unpredictable results within the author interface which is not prepared to handle mirrored content. You can loose data e.g. if you try to remove a superimposed content node in the author the original node and its subtree gets deleted in the JCR as well, because the AEM author used JCR and not the Sling CRUD API to do the delete operation.


### Comparison with Sling Resource Merger

In Sling Contrib a ["Apache Sling Resource Merger"](https://svn.apache.org/repos/asf/sling/trunk/contrib/extensions/resourcemerger) bundle is provided. Although both Sling Resource Merger and the Superimposing Resource Provider take care of mirroring and merging resources they solve quite different problems and have different usecases:

* Sling Resource Merger is primary about Merging resources of content structures from /apps and /libs, e.g. dialog definitions of an AEM application. It mounts the merged resources at a new path (e.g. /mnt/overlay) which can be included in the script resolution.
* The Superimposing Content Resource Provider is targeted at content. Think of a scenario with one master site that is rolled out to hundreds of slave sites with mostly identical contents but some site-specific overrides and customizations. This is not possible with Sling Resource Merger and vice versa.
