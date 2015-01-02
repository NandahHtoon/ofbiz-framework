/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.widget.tree;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.xml.parsers.ParserConfigurationException;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.GeneralException;
import org.ofbiz.base.util.StringUtil;
import org.ofbiz.base.util.UtilCodec;
import org.ofbiz.base.util.UtilFormatOut;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilHttp;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.UtilXml;
import org.ofbiz.base.util.collections.MapStack;
import org.ofbiz.base.util.string.FlexibleStringExpander;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.model.ModelEntity;
import org.ofbiz.entity.model.ModelField;
import org.ofbiz.entity.util.EntityListIterator;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.widget.ModelWidget;
import org.ofbiz.widget.ModelWidgetAction;
import org.ofbiz.widget.ModelWidgetVisitor;
import org.ofbiz.widget.WidgetWorker;
import org.ofbiz.widget.screen.ModelScreen;
import org.ofbiz.widget.screen.ScreenFactory;
import org.ofbiz.widget.screen.ScreenRenderException;
import org.ofbiz.widget.screen.ScreenStringRenderer;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Models the &lt;tree&gt; element.
 * 
 * @see <code>widget-tree.xsd</code>
 */
@SuppressWarnings("serial")
public class ModelTree extends ModelWidget {

    public static final String module = ModelTree.class.getName();

    protected String defaultEntityName;
    protected String defaultRenderStyle;
    protected FlexibleStringExpander defaultWrapStyleExdr;
    protected FlexibleStringExpander expandCollapseRequestExdr;
    protected boolean forceChildCheck;
    private final String location;
    protected List<ModelNode> nodeList = new ArrayList<ModelNode>();
    protected Map<String, ModelNode> nodeMap = new HashMap<String, ModelNode>();
    protected int openDepth;
    protected int postTrailOpenDepth;
    protected String rootNodeName;
    protected FlexibleStringExpander trailNameExdr;
    protected String treeLocation;

    // ===== CONSTRUCTORS =====
    /** Default Constructor */

    public ModelTree(Element treeElement, String location) {
        super(treeElement);
        this.location = location;
        this.rootNodeName = treeElement.getAttribute("root-node-name");
        this.defaultRenderStyle = UtilFormatOut.checkEmpty(treeElement.getAttribute("default-render-style"), "simple");
        // A temporary hack to accommodate those who might still be using "render-style" instead of "default-render-style"
        if (UtilValidate.isEmpty(this.defaultRenderStyle) || this.defaultRenderStyle.equals("simple")) {
            String rStyle = treeElement.getAttribute("render-style");
            if (UtilValidate.isNotEmpty(rStyle))
                this.defaultRenderStyle = rStyle;
        }
        this.defaultWrapStyleExdr = FlexibleStringExpander.getInstance(treeElement.getAttribute("default-wrap-style"));
        this.expandCollapseRequestExdr = FlexibleStringExpander.getInstance(treeElement.getAttribute("expand-collapse-request"));
        this.trailNameExdr = FlexibleStringExpander.getInstance(UtilFormatOut.checkEmpty(treeElement.getAttribute("trail-name"),
                "trail"));
        this.forceChildCheck = !"false".equals(treeElement.getAttribute("force-child-check"));
        this.defaultEntityName = treeElement.getAttribute("entity-name");
        try {
            openDepth = Integer.parseInt(treeElement.getAttribute("open-depth"));
        } catch (NumberFormatException e) {
            openDepth = 0;
        }
        try {
            postTrailOpenDepth = Integer.parseInt(treeElement.getAttribute("post-trail-open-depth"));
        } catch (NumberFormatException e) {
            postTrailOpenDepth = 999;
        }
        for (Element nodeElementEntry : UtilXml.childElementList(treeElement, "node")) {
            ModelNode node = new ModelNode(nodeElementEntry, this);
            String nodeName = node.getName();
            nodeList.add(node);
            nodeMap.put(nodeName, node);
        }
        if (nodeList.size() == 0) {
            throw new IllegalArgumentException("No node elements found for the tree definition with name: " + getName());
        }
    }

    @Override
    public void accept(ModelWidgetVisitor visitor) throws Exception {
        visitor.visit(this);
    }

    @Override
    public String getBoundaryCommentName() {
        return treeLocation + "#" + getName();
    }

    public String getDefaultEntityName() {
        return this.defaultEntityName;
    }

    public String getExpandCollapseRequest(Map<String, Object> context) {
        String expColReq = this.expandCollapseRequestExdr.expandString(context);
        if (UtilValidate.isEmpty(expColReq)) {
            HttpServletRequest request = (HttpServletRequest) context.get("request");
            String s1 = request.getRequestURI();
            int pos = s1.lastIndexOf("/");
            if (pos >= 0)
                expColReq = s1.substring(pos + 1);
            else
                expColReq = s1;
        }
        //append also the request parameters
        Map<String, Object> paramMap = UtilGenerics.checkMap(context.get("requestParameters"));
        if (UtilValidate.isNotEmpty(paramMap)) {
            Map<String, Object> requestParameters = new HashMap<String, Object>(paramMap);
            requestParameters.remove(this.getTrailName(context));
            if (UtilValidate.isNotEmpty(requestParameters)) {
                String queryString = UtilHttp.urlEncodeArgs(requestParameters, false);
                if (expColReq.indexOf("?") < 0) {
                    expColReq += "?";
                } else {
                    expColReq += "&amp;";
                }
                expColReq += queryString;
            }
        }
        return expColReq;
    }

    public int getOpenDepth() {
        return openDepth;
    }

    public int getPostTrailOpenDepth() {
        return postTrailOpenDepth;
    }

    public String getRenderStyle() {
        return this.defaultRenderStyle;
    }

    public String getRootNodeName() {
        return rootNodeName;
    }

    public String getTrailName(Map<String, Object> context) {
        return this.trailNameExdr.expandString(context);
    }

    public String getWrapStyle(Map<String, Object> context) {
        return this.defaultWrapStyleExdr.expandString(context);
    }

    /**
     * Renders this tree to a String, i.e. in a text format, as defined with the
     * TreeStringRenderer implementation.
     *
     * @param buf the StringBuffer Object
     * @param context Map containing the tree context; the following are
     *   reserved words in this context: parameters (Map), isError (Boolean),
     *   itemIndex (Integer, for lists only, otherwise null), bshInterpreter,
     *   treeName (String, optional alternate name for tree, defaults to the
     *   value of the name attribute)
     * @param treeStringRenderer An implementation of the TreeStringRenderer
     *   interface that is responsible for the actual text generation for
     *   different tree elements; implementing your own makes it possible to
     *   use the same tree definitions for many types of tree UIs
     */
    @SuppressWarnings("rawtypes")
    public void renderTreeString(StringBuffer buf, Map<String, Object> context, TreeStringRenderer treeStringRenderer)
            throws GeneralException {
        Map<String, Object> parameters = UtilGenerics.checkMap(context.get("parameters"));
        ModelNode node = nodeMap.get(rootNodeName);
        String trailName = trailNameExdr.expandString(context);
        String treeString = (String) context.get(trailName);
        if (UtilValidate.isEmpty(treeString)) {
            treeString = (String) parameters.get(trailName);
        }
        List<String> trail = null;
        if (UtilValidate.isNotEmpty(treeString)) {
            trail = StringUtil.split(treeString, "|");
            if (UtilValidate.isEmpty(trail))
                throw new RuntimeException("Tree 'trail' value is empty.");
            context.put("rootEntityId", trail.get(0));
            context.put(getDefaultPkName(context), trail.get(0));
        } else {
            trail = new LinkedList<String>();
        }
        context.put("targetNodeTrail", trail);
        context.put("currentNodeTrail", new LinkedList());
        StringWriter writer = new StringWriter();
        try {
            node.renderNodeString(writer, context, treeStringRenderer, 0);
            buf.append(writer.toString());
        } catch (IOException e2) {
            String errMsg = "Error rendering included label with name [" + getName() + "] : " + e2.toString();
            Debug.logError(e2, errMsg, module);
            throw new RuntimeException(errMsg);
        }
    }

    public String getDefaultPkName( Map<String, Object> context) {
        ModelEntity modelEntity = WidgetWorker.getDelegator(context).getModelEntity(this.defaultEntityName);
        if (modelEntity.getPksSize() == 1) {
            ModelField modelField = modelEntity.getOnlyPk();
            return modelField.getName();
        }
        return null;
    }

    public void setTreeLocation(String treeLocation) {
        this.treeLocation = treeLocation;
    }

    public static class ModelNode extends ModelWidget {

        private final List<ModelWidgetAction> actions;
        protected ModelTreeCondition condition;
        protected String entityName;
        protected String entryName;
        protected String expandCollapseStyle;
        protected Image image;
        protected Label label;
        protected Link link;
        protected ModelTree modelTree;
        protected String pkName;
        protected String renderStyle;
        protected FlexibleStringExpander screenLocationExdr;
        protected FlexibleStringExpander screenNameExdr;
        protected String shareScope;
        protected List<ModelSubNode> subNodeList = new ArrayList<ModelSubNode>();
        protected List<Object[]> subNodeValues;
        protected FlexibleStringExpander wrapStyleExdr;

        public ModelNode(Element nodeElement, ModelTree modelTree) {
            super(nodeElement);
            this.modelTree = modelTree;
            this.expandCollapseStyle = nodeElement.getAttribute("expand-collapse-style");
            this.wrapStyleExdr = FlexibleStringExpander.getInstance(nodeElement.getAttribute("wrap-style"));
            this.renderStyle = nodeElement.getAttribute("render-style");
            this.entryName = UtilFormatOut.checkEmpty(nodeElement.getAttribute("entry-name"), null);
            this.entityName = nodeElement.getAttribute("entity-name");
            if (this.pkName == null || nodeElement.hasAttribute("join-field-name"))
                this.pkName = nodeElement.getAttribute("join-field-name");
            ArrayList<ModelWidgetAction> actions = new ArrayList<ModelWidgetAction>();
            Element actionsElement = UtilXml.firstChildElement(nodeElement, "actions");
            if (actionsElement != null) {
                actions.addAll(ModelTreeAction.readNodeActions(this, actionsElement));
            }
            Element actionElement = UtilXml.firstChildElement(nodeElement, "entity-one");
            if (actionElement != null) {
                actions.add(new ModelWidgetAction.EntityOne(this, actionElement));
            }
            actionElement = UtilXml.firstChildElement(nodeElement, "service");
            if (actionElement != null) {
                actions.add(new ModelTreeAction.Service(this, actionElement));
            }
            actionElement = UtilXml.firstChildElement(nodeElement, "script");
            if (actionElement != null) {
                actions.add(new ModelTreeAction.Script(this, actionElement));
            }
            actions.trimToSize();
            this.actions = Collections.unmodifiableList(actions);
            Element screenElement = UtilXml.firstChildElement(nodeElement, "include-screen");
            if (screenElement != null) {
                this.screenNameExdr = FlexibleStringExpander.getInstance(screenElement.getAttribute("name"));
                this.screenLocationExdr = FlexibleStringExpander.getInstance(screenElement.getAttribute("location"));
                this.shareScope = screenElement.getAttribute("share-scope");
            }
            Element labelElement = UtilXml.firstChildElement(nodeElement, "label");
            if (labelElement != null) {
                this.label = new Label(labelElement);
            }
            Element linkElement = UtilXml.firstChildElement(nodeElement, "link");
            if (linkElement != null) {
                this.link = new Link(linkElement);
            }
            Element imageElement = UtilXml.firstChildElement(nodeElement, "image");
            if (imageElement != null) {
                this.image = new Image(imageElement);
            }
            Element conditionElement = UtilXml.firstChildElement(nodeElement, "condition");
            if (conditionElement != null) {
                this.condition = new ModelTreeCondition(modelTree, conditionElement);
            }
            for (Element subNodeElementEntry : UtilXml.childElementList(nodeElement, "sub-node")) {
                ModelSubNode subNode = new ModelSubNode(subNodeElementEntry, this);
                subNodeList.add(subNode);
            }
        }

        @Override
        public void accept(ModelWidgetVisitor visitor) throws Exception {
            visitor.visit(this);
        }

        public void getChildren(Map<String, Object> context) {
            this.subNodeValues = new ArrayList<Object[]>();
            for (ModelSubNode subNode : subNodeList) {
                String nodeName = subNode.getNodeName(context);
                ModelNode node = modelTree.nodeMap.get(nodeName);
                List<ModelWidgetAction> subNodeActions = subNode.getActions();
                //if (Debug.infoOn()) Debug.logInfo(" context.currentValue:" + context.get("currentValue"), module);
                ModelWidgetAction.runSubActions(subNodeActions, context);
                // List dataFound = (List)context.get("dataFound");
                Iterator<? extends Map<String, ? extends Object>> dataIter = subNode.getListIterator();
                if (dataIter instanceof EntityListIterator) {
                    EntityListIterator eli = (EntityListIterator) dataIter;
                    Map<String, Object> val = null;
                    while ((val = eli.next()) != null) {
                        Object[] arr = { node, val };
                        this.subNodeValues.add(arr);
                    }
                    try {
                        eli.close();
                    } catch (GenericEntityException e) {
                        Debug.logError(e, module);
                        throw new RuntimeException(e.getMessage());
                    }
                } else if (dataIter != null) {
                    while (dataIter.hasNext()) {
                        Map<String, ? extends Object> val = dataIter.next();
                        Object[] arr = { node, val };
                        this.subNodeValues.add(arr);
                    }
                }
            }
        }

        public String getEntityName() {
            if (UtilValidate.isNotEmpty(this.entityName)) {
                return this.entityName;
            } else {
                return this.modelTree.getDefaultEntityName();
            }
        }

        public String getEntryName() {
            return this.entryName;
        }

        public String getExpandCollapseStyle() {
            return expandCollapseStyle;
        }

        public ModelTree getModelTree() {
            return this.modelTree;
        }

        public String getPkName(Map<String, Object> context) {
            if (UtilValidate.isNotEmpty(this.pkName)) {
                return this.pkName;
            } else {
                return this.modelTree.getDefaultPkName(context);
            }
        }

        public String getRenderStyle() {
            String rStyle = this.renderStyle;
            if (UtilValidate.isEmpty(rStyle))
                rStyle = modelTree.getRenderStyle();
            return rStyle;
        }

        public String getWrapStyle(Map<String, Object> context) {
            String val = this.wrapStyleExdr.expandString(context);
            if (UtilValidate.isEmpty(val)) {
                val = this.modelTree.getWrapStyle(context);
            }
            return val;
        }

        public boolean hasChildren(Map<String, Object> context) {
            boolean hasChildren = false;
            Long nodeCount = null;
            String countFieldName = "childBranchCount";
            Object obj = null;
            if (UtilValidate.isNotEmpty(this.entryName)) {
                Map<String, Object> map = UtilGenerics.cast(context.get(this.entryName));
                if (map instanceof GenericValue) {
                    ModelEntity modelEntity = ((GenericValue) map).getModelEntity();
                    if (modelEntity.isField(countFieldName)) {
                        obj = map.get(countFieldName);
                    }
                }
            } else {
                obj = context.get(countFieldName);
            }
            if (obj != null) {
                nodeCount = (Long) obj;
            }
            String entName = this.getEntityName();
            Delegator delegator = WidgetWorker.getDelegator(context);
            ModelEntity modelEntity = delegator.getModelEntity(entName);
            ModelField modelField = null;
            if (modelEntity.isField(countFieldName)) {
                modelField = modelEntity.getField(countFieldName);
            }
            if (nodeCount == null && modelField != null || this.modelTree.forceChildCheck) {
                getChildren(context);
                /*
                String id = (String)context.get(modelTree.getPkName());
                if (UtilValidate.isNotEmpty(id)) {
                    try {
                        int leafCount = ContentManagementWorker.updateStatsTopDown(delegator, id, UtilMisc.toList("SUB_CONTENT", "PUBLISH_LINK"));
                        GenericValue entity = delegator.findOne(entName, UtilMisc.toMap(modelTree.getPkName(), id), true);
                        obj = entity.get("childBranchCount");
                       if (obj != null)
                           nodeCount = (Long)obj;
                    } catch (GenericEntityException e) {
                        Debug.logError(e, module);
                       throw new RuntimeException(e.getMessage());
                    }
                }
                */
                nodeCount = Long.valueOf(this.subNodeValues.size());
                String pkName = this.getPkName(context);
                String id = null;
                if (UtilValidate.isNotEmpty(this.entryName)) {
                    id = UtilGenerics.<Map<String, String>> cast(context.get(this.entryName)).get(pkName);
                } else {
                    id = (String) context.get(pkName);
                }
                try {
                    if (id != null && modelEntity.getPksSize() == 1) {
                        GenericValue entity = EntityQuery.use(delegator).from(entName).where(pkName, id).queryOne();
                        if (modelEntity.isField("childBranchCount")) {
                            entity.put("childBranchCount", nodeCount);
                            entity.store();
                        }
                    }
                } catch (GenericEntityException e) {
                    Debug.logError(e, module);
                    throw new RuntimeException(e.getMessage());
                }
            } else if (nodeCount == null) {
                getChildren(context);
                if (subNodeValues != null) {
                    nodeCount = Long.valueOf(subNodeValues.size());
                }
            }
            if (nodeCount != null && nodeCount.intValue() > 0) {
                hasChildren = true;
            }
            return hasChildren;
        }

        public boolean isExpandCollapse() {
            boolean isExpCollapse = false;
            String rStyle = getRenderStyle();
            if (rStyle != null && rStyle.equals("expand-collapse"))
                isExpCollapse = true;
            return isExpCollapse;
        }

        public boolean isFollowTrail() {
            boolean isFollowTrail = false;
            String rStyle = getRenderStyle();
            if (rStyle != null && (rStyle.equals("follow-trail") || rStyle.equals("show-peers") || rStyle.equals("follow-trail"))) {
                isFollowTrail = true;
            }
            return isFollowTrail;
        }

        public boolean isRootNode() {
            return getName().equals(modelTree.getRootNodeName());
        }

        public void renderNodeString(Appendable writer, Map<String, Object> context, TreeStringRenderer treeStringRenderer,
                int depth) throws IOException, GeneralException {
            boolean passed = true;
            if (this.condition != null) {
                if (!this.condition.eval(context)) {
                    passed = false;
                }
            }
            //Debug.logInfo("in ModelMenu, name:" + this.getName(), module);
            if (passed) {
                List<String> currentNodeTrail = UtilGenerics.toList(context.get("currentNodeTrail"));
                context.put("processChildren", Boolean.TRUE);
                // this action will usually obtain the "current" entity
                ModelTreeAction.runSubActions(this.actions, context);
                String pkName = getPkName(context);
                String id = null;
                if (UtilValidate.isNotEmpty(this.entryName)) {
                    id = UtilGenerics.<Map<String, String>> cast(context.get(this.entryName)).get(pkName);
                } else {
                    id = (String) context.get(pkName);
                }
                currentNodeTrail.add(id);
                treeStringRenderer.renderNodeBegin(writer, context, this, depth);
                //if (Debug.infoOn()) Debug.logInfo(" context:" +
                // context.entrySet(), module);
                try {
                    String screenName = null;
                    if (screenNameExdr != null)
                        screenName = screenNameExdr.expandString(context);
                    String screenLocation = null;
                    if (screenLocationExdr != null)
                        screenLocation = screenLocationExdr.expandString(context);
                    if (screenName != null && screenLocation != null) {
                        ScreenStringRenderer screenStringRenderer = treeStringRenderer.getScreenStringRenderer(context);
                        ModelScreen modelScreen = ScreenFactory.getScreenFromLocation(screenLocation, screenName);
                        modelScreen.renderScreenString(writer, context, screenStringRenderer);
                    }
                    if (label != null) {
                        label.renderLabelString(writer, context, treeStringRenderer);
                    }
                    if (link != null) {
                        link.renderLinkString(writer, context, treeStringRenderer);
                    }
                    treeStringRenderer.renderLastElement(writer, context, this);
                    Boolean processChildren = (Boolean) context.get("processChildren");
                    //if (Debug.infoOn()) Debug.logInfo(" processChildren:" + processChildren, module);
                    if (processChildren.booleanValue()) {
                        getChildren(context);
                        int newDepth = depth + 1;
                        for (Object[] arr : this.subNodeValues) {
                            ModelNode node = (ModelNode) arr[0];
                            Map<String, Object> val = UtilGenerics.checkMap(arr[1]);
                            //GenericPK pk = val.getPrimaryKey();
                            //if (Debug.infoOn()) Debug.logInfo(" pk:" + pk,
                            // module);
                            String thisPkName = node.getPkName(context);
                            String thisEntityId = (String) val.get(thisPkName);
                            MapStack<String> newContext = MapStack.create(context);
                            newContext.push();
                            String nodeEntryName = node.getEntryName();
                            if (UtilValidate.isNotEmpty(nodeEntryName)) {
                                newContext.put(nodeEntryName, val);
                            } else {
                                newContext.putAll(val);
                            }
                            String targetEntityId = null;
                            List<String> targetNodeTrail = UtilGenerics.checkList(context.get("targetNodeTrail"));
                            if (newDepth < targetNodeTrail.size()) {
                                targetEntityId = targetNodeTrail.get(newDepth);
                            }
                            if ((targetEntityId != null && targetEntityId.equals(thisEntityId))
                                    || this.showPeers(newDepth, context)) {
                                node.renderNodeString(writer, newContext, treeStringRenderer, newDepth);
                            }
                        }
                    }
                } catch (ScreenRenderException e) {
                    String errMsg = "Error rendering included label with name [" + getName() + "] : " + e.toString();
                    Debug.logError(e, errMsg, module);
                    throw new RuntimeException(errMsg);
                } catch (SAXException e) {
                    String errMsg = "Error rendering included label with name [" + getName() + "] : " + e.toString();
                    Debug.logError(e, errMsg, module);
                    throw new RuntimeException(errMsg);
                } catch (ParserConfigurationException e3) {
                    String errMsg = "Error rendering included label with name [" + getName() + "] : " + e3.toString();
                    Debug.logError(e3, errMsg, module);
                    throw new RuntimeException(errMsg);
                } catch (IOException e2) {
                    String errMsg = "Error rendering included label with name [" + getName() + "] : " + e2.toString();
                    Debug.logError(e2, errMsg, module);
                    throw new RuntimeException(errMsg);
                }
                treeStringRenderer.renderNodeEnd(writer, context, this);
                int removeIdx = currentNodeTrail.size() - 1;
                if (removeIdx >= 0)
                    currentNodeTrail.remove(removeIdx);
            }
        }

        public void setPkName(String pkName) {
            this.pkName = pkName;
        }

        public boolean showPeers(int currentDepth, Map<String, Object> context) {
            int trailSize = 0;
            List<?> trail = UtilGenerics.checkList(context.get("targetNodeTrail"));
            int openDepth = modelTree.getOpenDepth();
            int postTrailOpenDepth = modelTree.getPostTrailOpenDepth();
            if (trail != null)
                trailSize = trail.size();

            boolean showPeers = false;
            String rStyle = getRenderStyle();
            if (rStyle == null) {
                showPeers = true;
            } else if (!isFollowTrail()) {
                showPeers = true;
            } else if ((currentDepth < trailSize) && (rStyle != null)
                    && (rStyle.equals("show-peers") || rStyle.equals("expand-collapse"))) {
                showPeers = true;
            } else if (openDepth >= currentDepth) {
                showPeers = true;
            } else {
                int depthAfterTrail = currentDepth - trailSize;
                if (depthAfterTrail >= 0 && depthAfterTrail <= postTrailOpenDepth)
                    showPeers = true;
            }
            return showPeers;
        }

        public static class Image {

            protected FlexibleStringExpander borderExdr;
            protected FlexibleStringExpander heightExdr;
            protected FlexibleStringExpander idExdr;
            protected FlexibleStringExpander srcExdr;
            protected FlexibleStringExpander styleExdr;
            protected String urlMode;
            protected FlexibleStringExpander widthExdr;

            public Image() {
                setSrc(null);
                setId(null);
                setStyle(null);
                setWidth(null);
                setHeight(null);
                setBorder("0");
                setUrlMode(null);
            }

            public Image(Element imageElement) {
                setSrc(imageElement.getAttribute("src"));
                setId(imageElement.getAttribute("id"));
                setStyle(imageElement.getAttribute("style"));
                setWidth(imageElement.getAttribute("width"));
                setHeight(imageElement.getAttribute("height"));
                setBorder(UtilFormatOut.checkEmpty(imageElement.getAttribute("border"), "0"));
                setUrlMode(UtilFormatOut.checkEmpty(imageElement.getAttribute("url-mode"), "content"));
            }

            public String getBorder(Map<String, Object> context) {
                return this.borderExdr.expandString(context);
            }

            public String getHeight(Map<String, Object> context) {
                return this.heightExdr.expandString(context);
            }

            public String getId(Map<String, Object> context) {
                return this.idExdr.expandString(context);
            }

            public String getSrc(Map<String, Object> context) {
                return this.srcExdr.expandString(context);
            }

            public String getStyle(Map<String, Object> context) {
                return this.styleExdr.expandString(context);
            }

            public String getUrlMode() {
                return this.urlMode;
            }

            public String getWidth(Map<String, Object> context) {
                return this.widthExdr.expandString(context);
            }

            public void renderImageString(Appendable writer, Map<String, Object> context, TreeStringRenderer treeStringRenderer) {
                try {
                    treeStringRenderer.renderImage(writer, context, this);
                } catch (IOException e) {
                    String errMsg = "Error rendering image with id [" + getId(context) + "]: " + e.toString();
                    Debug.logError(e, errMsg, module);
                    throw new RuntimeException(errMsg);
                }
            }

            public void setBorder(String val) {
                this.borderExdr = FlexibleStringExpander.getInstance(val);
            }

            public void setHeight(String val) {
                this.heightExdr = FlexibleStringExpander.getInstance(val);
            }

            public void setId(String val) {
                this.idExdr = FlexibleStringExpander.getInstance(val);
            }

            public void setSrc(String val) {
                String textAttr = UtilFormatOut.checkNull(val);
                this.srcExdr = FlexibleStringExpander.getInstance(textAttr);
            }

            public void setStyle(String val) {
                this.styleExdr = FlexibleStringExpander.getInstance(val);
            }

            public void setUrlMode(String val) {
                if (UtilValidate.isEmpty(val)) {
                    this.urlMode = "content";
                } else {
                    this.urlMode = val;
                }
            }

            public void setWidth(String val) {
                this.widthExdr = FlexibleStringExpander.getInstance(val);
            }
        }

        public static class Label {
            protected FlexibleStringExpander idExdr;
            protected FlexibleStringExpander styleExdr;
            protected FlexibleStringExpander textExdr;

            public Label(Element labelElement) {
                // put the text attribute first, then the pcdata under the element, if both are there of course
                String textAttr = UtilFormatOut.checkNull(labelElement.getAttribute("text"));
                String pcdata = UtilFormatOut.checkNull(UtilXml.elementValue(labelElement));
                this.textExdr = FlexibleStringExpander.getInstance(textAttr + pcdata);
                this.idExdr = FlexibleStringExpander.getInstance(labelElement.getAttribute("id"));
                this.styleExdr = FlexibleStringExpander.getInstance(labelElement.getAttribute("style"));
            }

            public String getId(Map<String, Object> context) {
                return this.idExdr.expandString(context);
            }

            public String getStyle(Map<String, Object> context) {
                return this.styleExdr.expandString(context);
            }

            public String getText(Map<String, Object> context) {
                String text = this.textExdr.expandString(context);
                UtilCodec.SimpleEncoder simpleEncoder = (UtilCodec.SimpleEncoder) context.get("simpleEncoder");
                if (simpleEncoder != null) {
                    text = simpleEncoder.encode(text);
                }
                return text;
            }

            public void renderLabelString(Appendable writer, Map<String, Object> context, TreeStringRenderer treeStringRenderer) {
                try {
                    treeStringRenderer.renderLabel(writer, context, this);
                } catch (IOException e) {
                    String errMsg = "Error rendering label with id [" + getId(context) + "]: " + e.toString();
                    Debug.logError(e, errMsg, module);
                    throw new RuntimeException(errMsg);
                }
            }
        }

        public static class Link {
            protected boolean encode = false;
            protected boolean fullPath = false;
            protected FlexibleStringExpander idExdr;
            protected Image image;
            protected String linkType;
            protected FlexibleStringExpander nameExdr;
            protected List<WidgetWorker.Parameter> parameterList = new ArrayList<WidgetWorker.Parameter>();
            protected FlexibleStringExpander prefixExdr;
            protected boolean secure = false;
            protected FlexibleStringExpander styleExdr;
            protected FlexibleStringExpander targetExdr;
            protected FlexibleStringExpander targetWindowExdr;
            protected FlexibleStringExpander textExdr;
            protected FlexibleStringExpander titleExdr;
            protected String urlMode = "intra-app";

            public Link() {
                setText(null);
                setId(null);
                setStyle(null);
                setTarget(null);
                setTargetWindow(null);
                setPrefix(null);
                setUrlMode(null);
                setFullPath(null);
                setSecure(null);
                setEncode(null);
                setName(null);
                setTitle(null);
            }

            public Link(Element linkElement) {
                setText(linkElement.getAttribute("text"));
                setId(linkElement.getAttribute("id"));
                setStyle(linkElement.getAttribute("style"));
                setTarget(linkElement.getAttribute("target"));
                setTargetWindow(linkElement.getAttribute("target-window"));
                setPrefix(linkElement.getAttribute("prefix"));
                setUrlMode(linkElement.getAttribute("url-mode"));
                setFullPath(linkElement.getAttribute("full-path"));
                setSecure(linkElement.getAttribute("secure"));
                setEncode(linkElement.getAttribute("encode"));
                setName(linkElement.getAttribute("name"));
                setTitle(linkElement.getAttribute("title"));
                Element imageElement = UtilXml.firstChildElement(linkElement, "image");
                if (imageElement != null) {
                    this.image = new Image(imageElement);
                }
                this.linkType = linkElement.getAttribute("link-type");
                List<? extends Element> parameterElementList = UtilXml.childElementList(linkElement, "parameter");
                for (Element parameterElement : parameterElementList) {
                    this.parameterList.add(new WidgetWorker.Parameter(parameterElement));
                }
            }

            public boolean getEncode() {
                return this.encode;
            }

            public boolean getFullPath() {
                return this.fullPath;
            }

            public String getId(Map<String, Object> context) {
                return this.idExdr.expandString(context);
            }

            public Image getImage() {
                return this.image;
            }

            public String getLinkType() {
                return this.linkType;
            }

            public String getName(Map<String, Object> context) {
                return this.nameExdr.expandString(context);
            }

            public Map<String, String> getParameterMap(Map<String, Object> context) {
                Map<String, String> fullParameterMap = new HashMap<String, String>();
                /* leaving this here... may want to add it at some point like the hyperlink element:
                Map<String, String> addlParamMap = this.parametersMapAcsr.get(context);
                if (addlParamMap != null) {
                    fullParameterMap.putAll(addlParamMap);
                }
                */
                for (WidgetWorker.Parameter parameter : this.parameterList) {
                    fullParameterMap.put(parameter.getName(), parameter.getValue(context));
                }
                return fullParameterMap;
            }

            public String getPrefix(Map<String, Object> context) {
                return this.prefixExdr.expandString(context);
            }

            public boolean getSecure() {
                return this.secure;
            }

            public String getStyle(Map<String, Object> context) {
                return this.styleExdr.expandString(context);
            }

            public String getTarget(Map<String, Object> context) {
                UtilCodec.SimpleEncoder simpleEncoder = (UtilCodec.SimpleEncoder) context.get("simpleEncoder");
                if (simpleEncoder != null) {
                    return this.targetExdr.expandString(UtilCodec.HtmlEncodingMapWrapper.getHtmlEncodingMapWrapper(context,
                            simpleEncoder));
                } else {
                    return this.targetExdr.expandString(context);
                }
            }

            public String getTargetWindow(Map<String, Object> context) {
                return this.targetWindowExdr.expandString(context);
            }

            public String getText(Map<String, Object> context) {
                String text = this.textExdr.expandString(context);
                UtilCodec.SimpleEncoder simpleEncoder = (UtilCodec.SimpleEncoder) context.get("simpleEncoder");
                if (simpleEncoder != null) {
                    text = simpleEncoder.encode(text);
                }
                return text;
            }

            public String getTitle(Map<String, Object> context) {
                String title = this.titleExdr.expandString(context);
                UtilCodec.SimpleEncoder simpleEncoder = (UtilCodec.SimpleEncoder) context.get("simpleEncoder");
                if (simpleEncoder != null) {
                    title = simpleEncoder.encode(title);
                }
                return title;
            }

            public String getUrlMode() {
                return this.urlMode;
            }

            public void renderLinkString(Appendable writer, Map<String, Object> context, TreeStringRenderer treeStringRenderer) {
                try {
                    treeStringRenderer.renderLink(writer, context, this);
                } catch (IOException e) {
                    String errMsg = "Error rendering link with id [" + getId(context) + "]: " + e.toString();
                    Debug.logError(e, errMsg, module);
                    throw new RuntimeException(errMsg);
                }
            }

            public void setEncode(String val) {
                String sEncode = val;
                if (sEncode != null && sEncode.equalsIgnoreCase("true"))
                    this.encode = true;
                else
                    this.encode = false;
            }

            public void setFullPath(String val) {
                String sFullPath = val;
                if (sFullPath != null && sFullPath.equalsIgnoreCase("true"))
                    this.fullPath = true;
                else
                    this.fullPath = false;
            }

            public void setId(String val) {
                this.idExdr = FlexibleStringExpander.getInstance(val);
            }

            public void setImage(Image img) {
                this.image = img;
            }

            public void setName(String val) {
                this.nameExdr = FlexibleStringExpander.getInstance(val);
            }

            public void setPrefix(String val) {
                this.prefixExdr = FlexibleStringExpander.getInstance(val);
            }

            public void setSecure(String val) {
                String sSecure = val;
                if (sSecure != null && sSecure.equalsIgnoreCase("true"))
                    this.secure = true;
                else
                    this.secure = false;
            }

            public void setStyle(String val) {
                this.styleExdr = FlexibleStringExpander.getInstance(val);
            }

            public void setTarget(String val) {
                this.targetExdr = FlexibleStringExpander.getInstance(val);
            }

            public void setTargetWindow(String val) {
                this.targetWindowExdr = FlexibleStringExpander.getInstance(val);
            }

            public void setText(String val) {
                String textAttr = UtilFormatOut.checkNull(val);
                this.textExdr = FlexibleStringExpander.getInstance(textAttr);
            }

            public void setTitle(String val) {
                this.titleExdr = FlexibleStringExpander.getInstance(val);
            }

            public void setUrlMode(String val) {
                if (UtilValidate.isNotEmpty(val))
                    this.urlMode = val;
            }

        }

        public static class ModelSubNode extends ModelWidget {

            private final List<ModelWidgetAction> actions;
            protected ListIterator<? extends Map<String, ? extends Object>> listIterator;
            protected FlexibleStringExpander nodeNameExdr;
            protected ModelNode rootNode;

            public ModelSubNode(Element subNodeElement, ModelNode modelNode) {
                super(subNodeElement);
                this.rootNode = modelNode;
                this.nodeNameExdr = FlexibleStringExpander.getInstance(subNodeElement.getAttribute("node-name"));
                ArrayList<ModelWidgetAction> actions = new ArrayList<ModelWidgetAction>();
                Element actionsElement = UtilXml.firstChildElement(subNodeElement, "actions");
                if (actionsElement != null) {
                    actions.addAll(ModelTreeAction.readNodeActions(this, actionsElement));
                }
                Element actionElement = UtilXml.firstChildElement(subNodeElement, "entity-and");
                if (actionElement != null) {
                    actions.add(new ModelTreeAction.EntityAnd(this, actionElement));
                }
                actionElement = UtilXml.firstChildElement(subNodeElement, "service");
                if (actionElement != null) {
                    actions.add(new ModelTreeAction.Service(this, actionElement));
                }
                actionElement = UtilXml.firstChildElement(subNodeElement, "entity-condition");
                if (actionElement != null) {
                    actions.add(new ModelTreeAction.EntityCondition(this, actionElement));
                }
                actionElement = UtilXml.firstChildElement(subNodeElement, "script");
                if (actionElement != null) {
                    actions.add(new ModelTreeAction.Script(this, actionElement));
                }
                actions.trimToSize();
                this.actions = Collections.unmodifiableList(actions);
            }

            @Override
            public void accept(ModelWidgetVisitor visitor) throws Exception {
                visitor.visit(this);
            }

            public List<ModelWidgetAction> getActions() {
                return actions;
            }

            public ListIterator<? extends Map<String, ? extends Object>> getListIterator() {
                return listIterator;
            }

            public ModelTree.ModelNode getNode() {
                return this.rootNode;
            }

            public String getNodeName(Map<String, Object> context) {
                return this.nodeNameExdr.expandString(context);
            }

            public void setListIterator(ListIterator<? extends Map<String, ? extends Object>> iter) {
                listIterator = iter;
            }
        }
    }
}
