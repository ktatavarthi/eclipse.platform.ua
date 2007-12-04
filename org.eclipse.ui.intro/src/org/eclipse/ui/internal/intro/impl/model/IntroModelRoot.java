/*******************************************************************************
 * Copyright (c) 2004, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ui.internal.intro.impl.model;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Vector;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Preferences;
import org.eclipse.core.runtime.SafeRunner;
import org.eclipse.help.UAContentFilter;
import org.eclipse.help.internal.UAElementFactory;
import org.eclipse.jface.util.SafeRunnable;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.internal.intro.impl.IntroPlugin;
import org.eclipse.ui.internal.intro.impl.model.loader.IntroContentParser;
import org.eclipse.ui.internal.intro.impl.model.loader.ModelLoaderUtil;
import org.eclipse.ui.internal.intro.impl.model.util.BundleUtil;
import org.eclipse.ui.internal.intro.impl.model.util.ModelUtil;
import org.eclipse.ui.internal.intro.impl.util.IntroEvaluationContext;
import org.eclipse.ui.internal.intro.impl.util.Log;
import org.eclipse.ui.internal.intro.impl.util.StringUtil;
import org.eclipse.ui.intro.config.IntroConfigurer;
import org.osgi.framework.Bundle;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * The root class for the OOBE model. It loads the configuration into the
 * appropriate classes.
 * 
 * Model rules:
 * <ol>
 * <li>if an attribute is not included in the markup, its value will be null in
 * the model.</li>
 * <li>Resources in plugin.xml are not implicitly resolved against $nl$.
 * Resources in pages are implicitly resolved against $nl$
 * <li>the current page id is set silently when loading the model. You do not
 * need the event notification on model load.</li>
 * <li>Children of a given parent (ie: model root, page, or group) *must* have
 * distinctive IDs otherwise resolving includes and extensions may fail.</li>
 * <li>Containers have the concept of loading children and resolving children.
 * At the model root level, resolving children means resolving ALL extensions of
 * model. At the container level, resolving children means resolving includes.
 * </li>
 * <li>Extensions are resolved before includes at the container level to avoid
 * race conditions. eg: if a page includes a shared group and an extension
 * extends this shared group, you want the include to get the extended group and
 * not the original group.</li>
 * <li>Resolving extensions should not resolve includes. No need to load other
 * models when we dont have to. Plus, extensions can only reference anchors, and
 * so no need to resolve includes.</li>
 * <li>Extensions can not target containers *after* they are resolved. For
 * example, an extension can not target a shared group after it has been
 * included in a page. It can target the initial shared group as a path, but not
 * the group in the page as a path. Again this is because extensions extends
 * anchors that already have a path, not a resolved path.</li>
 * <li>Pages and shared groups that are contributed through extensions become
 * children of the atrget configuration, and so any includes they may have will
 * be resolved correctly.</li>
 * <li>An infinite loop can occur if page A includes from page B and page B in
 * turn includes from page A. ie: cyclic includes. For performnace, accept.
 * </li>
 * <li>When resolving includes, if the target is a container, it must be
 * resolved to resolve its includes correctly. Otherwise, included includes will
 * fail due to reparenting.</li>
 * <li>unresolved includes are left as children of the parent container.</li>
 * <li>Unresolved extensions are left as children of the targetted model.</li>
 * <li>For dynamic awarness, the model is nulled and then reloaded. However, we
 * need to preserve the presentation instance since the UI is already loaded.
 * This is done by reloading the model, and directly resetting the presentation
 * to what it was.</li>
 * <li>Model classes should not have DOM classes as instance vars, and if this
 * is a must, null the DOM class instance the minute you are done. This is
 * because you want the VM to garbage collect the DOM model. Keeping a reference
 * to the DOM model from the Intro model will prevent that.</li>
 * </ol>
 * <li>(since 3.0.2) several passes are used to resolve contributions to
 * anchors that themselves where contributed through an extension. Each time a
 * contribution is resolved, the model tries to resolve all unresolved
 * contribution, recursively.
 * </ul>
 */
public class IntroModelRoot extends AbstractIntroContainer {

    /**
     * Model constants that fire property change event when they are changed in
     * the model.
     */
    public static final int CURRENT_PAGE_PROPERTY_ID = 1;

    private static final String ATT_CONTENT = "content"; //$NON-NLS-1$
    private static final String ATT_CONFIGURER = "configurer"; //$NON-NLS-1$
    private static final String VAR_THEME = "theme";  //$NON-NLS-1$

    // False if there is no valid contribution to the
    // org.eclipse.ui.intro.config extension point. Start off with true, and set
    // to false whenever something bad happens.
    private boolean hasValidConfig = true;
    private boolean isdynamicIntro;
    private IntroConfigurer configurer;
    private IntroTheme theme;
    private IntroPartPresentation introPartPresentation;
    private IntroHomePage homePage;
    private String currentPageId;
    private String startPageId;
    private IntroHomePage standbyPage;

    // the config extensions for this model.
    private IConfigurationElement[] configExtensionElements;

    // maintain listener list for model changes.
    public ListenerList propChangeListeners = new ListenerList();

    // a hashtable to hold all loaded DOMs until resolving all configExtensions
    // is done. Key is one extensionContent DOM element, while value is the
    // IConfigurationElement from where it was loaded. This is needed to extract
    // the base for the xml content file.
    private Hashtable unresolvedConfigExt = new Hashtable();


    /**
     * Model root. Takes a configElement that represents <config>in the
     * plugin.xml markup AND all the extension contributed to this model through
     * the configExtension point.
     */
    public IntroModelRoot(IConfigurationElement configElement,
            IConfigurationElement[] configExtensionElements) {
        // the config element that represents the correct model root.
        super(configElement);
        this.configExtensionElements = configExtensionElements;

    }

    public void loadModel() {
        getChildren();
    }

    /**
     * Loads the full model. The children of a model root are the presentation,
     * followed by all pages, and all shared groups. Then if the model has
     * extension, its the unresolved container extensions, followed by all
     * extension pages and groups. The presentation is loaded from the
     * IConfiguration element representing the config. All else is loaded from
     * xml content file.
     * 
     */
    protected void loadChildren() {
        children = new Vector();
        if (Log.logInfo)
            Log.info("Creating Intro plugin model...."); //$NON-NLS-1$

        // load presentation first and create the model class for it. If there
        // is more than one presentation, load first one, and log rest.
        IConfigurationElement presentationElement = loadPresentation();
        if (presentationElement == null) {
            // no presentations at all, exit.
            setModelState(true, false, false);
            Log.warning("Could not find presentation element in intro config."); //$NON-NLS-1$
            return;
        }
        
        loadTheme();
        loadConfigurer();

        introPartPresentation = new IntroPartPresentation(presentationElement);
        children.add(introPartPresentation);
        // set parent.
        introPartPresentation.setParent(this);

        // now load all children of the config. There should only be pages and
        // groups here. And order is not important. These elements are loaded
        // from the content file DOM.
        Document document = loadDOM(getCfgElement());
        if (document == null) {
            // we failed to parse the content file. Intro Parser would have
            // logged the fact. Parser would also have checked to see if the
            // content file has the correct root tag.
            setModelState(true, false, false);
            return;
        }

        // set base for this model.
        this.base = getBase(getCfgElement());

        // now load content.
        loadPages(document, getBundle());
        loadSharedGroups(document, getBundle());

        // Attributes of root page decide if we have a static or dynamic case.
        setModelState(true, true, getHomePage().isDynamic());
    }

    /**
     * Sets the presentation to the given presentation. The model always has the
     * presentation as the first child, so use that fact. This method is used
     * for dynamic awarness to enable replacing the new presentation with the
     * existing one after a model refresh.
     * 
     * @param presentation
     */
    public void setPresentation(IntroPartPresentation presentation) {
        this.introPartPresentation = presentation;
        presentation.setParent(this);
        children.set(0, presentation);
    }

    /**
     * Resolve contributions into this container's children.
     */
    protected void resolveChildren() {
        // now handle config extension.
        resolveConfigExtensions();
        resolved = true;
    }

    private IConfigurationElement loadPresentation() {
        // If there is more than one presentation, load first one, and log
        // rest.
        IConfigurationElement[] presentationElements = getCfgElement()
            .getChildren(IntroPartPresentation.TAG_PRESENTATION);

        IConfigurationElement presentationElement = ModelLoaderUtil
            .validateSingleContribution(presentationElements,
                IntroPartPresentation.ATT_HOME_PAGE_ID);
        return presentationElement;
    }
    
    private void loadConfigurer() {
    	String cname = getCfgElement().getAttribute(ATT_CONFIGURER);
    	if (cname!=null) {
    		try {
    			Object obj = getCfgElement().createExecutableExtension(ATT_CONFIGURER);
    			if (obj instanceof IntroConfigurer)
    				configurer = (IntroConfigurer)obj;
    		}
    		catch (CoreException e) {
    			Log.error("Error loading intro configurer", e); //$NON-NLS-1$
    		}
    	}
    }
    
    private void loadTheme() {
    	Preferences pref = IntroPlugin.getDefault().getPluginPreferences();
    	String pid = Platform.getProduct().getId();
    	String themeId = pref.getString(pid+"_INTRO_THEME"); //$NON-NLS-1$
    	if (themeId.length()==0)
    		themeId = pref.getString("INTRO_THEME"); //$NON-NLS-1$
    	startPageId = pref.getString(pid + "_INTRO_START_PAGE"); //$NON-NLS-1$
    	if (startPageId.length() == 0) {
    		startPageId = pref.getString("INTRO_START_PAGE"); //$NON-NLS-1$
    	}
    	IConfigurationElement [] elements = Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.intro.configExtension"); //$NON-NLS-1$
    	IConfigurationElement themeElement=null;
    	for (int i=0; i<elements.length; i++) {
    		if (elements[i].getName().equals("theme")) { //$NON-NLS-1$
    			String id = elements[i].getAttribute("id"); //$NON-NLS-1$
    			if (themeId!=null) {
    				if (id!=null && themeId.equals(id)) {
    					// use this one
    					themeElement = elements[i];
    					break;
    				}
    			}
    			else {
    				// see if this one is the default
    				String value = elements[i].getAttribute("default"); //$NON-NLS-1$
    				if (value!=null && value.equalsIgnoreCase("true")) { //$NON-NLS-1$
    					themeElement = elements[i];
    					break;
    				}
    			}
    		}
    	}
    	if (themeElement!=null) {
    		theme = new IntroTheme(themeElement);
    	}
    }

    /**
     * Loads all pages defined in this config from the xml content file.
     */
    private void loadPages(Document dom, Bundle bundle) {
        String homePageId = getPresentation().getHomePageId();
        String standbyPageId = getPresentation().getStandbyPageId();
        Element[] pages = ModelUtil.getElementsByTagName(dom,
            AbstractIntroPage.TAG_PAGE);
        for (int i = 0; i < pages.length; i++) {
            Element pageElement = pages[i];
            if (pageElement.getAttribute(AbstractIntroIdElement.ATT_ID).equals(
                homePageId)) {
                // Create the model class for the Root Page.
                homePage = new IntroHomePage(pageElement, bundle, base);
                homePage.setParent(this);
                currentPageId = homePage.getId();
                children.add(homePage);
            } else if (pageElement.getAttribute(AbstractIntroIdElement.ATT_ID)
                .equals(standbyPageId)) {
                // Create the model class for the standby Page.
                standbyPage = new IntroHomePage(pageElement, bundle, base);
                standbyPage.setParent(this);
                // signal that it is a standby page.
                standbyPage.setStandbyPage(true);
                children.add(standbyPage);
            } else {
                // Create the model class for an intro Page.
                IntroPage page = new IntroPage(pageElement, bundle, base);
                page.setParent(this);
                children.add(page);
            }
        }
    }

    /**
     * Loads all shared groups defined in this config, from the DOM.
     */
    private void loadSharedGroups(Document dom, Bundle bundle) {
        Element[] groups = ModelUtil.getElementsByTagName(dom,
            IntroGroup.TAG_GROUP);
        for (int i = 0; i < groups.length; i++) {
            IntroGroup group = new IntroGroup(groups[i], bundle, base);
            group.setParent(this);
            children.add(group);
        }
    }

    /**
     * Handles all the configExtensions to this current model. Resolving
     * configExts means finding target anchor and inserting extension content at
     * target. Also, several passes are used to resolve as many extensions as
     * possible. This allows for resolving nested anchors (ie: anchors to
     * anchors in contributions).
     */
    private void resolveConfigExtensions() {
        for (int i = 0; i < configExtensionElements.length; i++) {
            processConfigExtension(configExtensionElements[i]);
        }
        
        tryResolvingExtensions();
        
        // At this stage all pages will be resolved, some contributions may not be

        // now add all unresolved extensions as model children and log fact.
        Enumeration keys = unresolvedConfigExt.keys();
        while (keys.hasMoreElements()) {
            Element configExtensionElement = (Element) keys.nextElement();
            IConfigurationElement configExtConfigurationElement = (IConfigurationElement) unresolvedConfigExt
                .get(configExtensionElement);
            Bundle bundle = BundleUtil
                .getBundleFromConfigurationElement(configExtConfigurationElement);
            String base = getBase(configExtConfigurationElement);
            children.add(new IntroExtensionContent(configExtensionElement,
                bundle, base, configExtConfigurationElement));

            // INTRO: fix log strings.
            Log
                .warning("Could not resolve the following configExtension: " //$NON-NLS-1$
                        + ModelLoaderUtil.getLogString(bundle,
                            configExtensionElement,
                            IntroExtensionContent.ATT_PATH));
        }
    }

    private void processConfigExtension(IConfigurationElement configExtElement) {
        // This call will extract the parent folder if needed.
        Document dom = loadDOM(configExtElement);
        if (dom == null)
            // we failed to parse the content file. Intro Parser would
            // have logged the fact. Parser would also have checked to
            // see if the content file has the correct root tag.
            return;
        processConfigExtension(dom, configExtElement);
    }


    private void processConfigExtension(Document dom,
            IConfigurationElement configExtElement) {

        // Find the target of this container extension, and add all its
        // children to target. Make sure to pass correct bundle and base to
        // propagate to all children.
        String base = getBase(configExtElement);
        Element[] extensionContentElements = loadExtensionContent(dom,
            configExtElement, base);
        for (int i = 0; i < extensionContentElements.length; i++) {
        	Element extensionContentElement = extensionContentElements[i];
        	 if (extensionContentElement.hasAttribute("failed")) { //$NON-NLS-1$
                 // we failed to resolve this configExtension, because target
                 // could not be found or is not an anchor, add the extension to the
                 // list of unresolved configExtensions.
                 // INTRO: an extensionContent is used as a key, instead of the whole
                 // DOM. This is useful if we need to support multiple extension
                 // contents in one file.
                 if (!unresolvedConfigExt.containsKey(extensionContentElement))
                     unresolvedConfigExt.put(extensionContentElement,
                         configExtElement);
             }
        }

        // Now load all pages and shared groups
        // from this config extension. Get the bundle from the extensions since they are
        // defined in other plugins.
        Bundle bundle = BundleUtil
            .getBundleFromConfigurationElement(configExtElement);

        Element[] pages = ModelUtil.getElementsByTagName(dom,
            AbstractIntroPage.TAG_PAGE);
        for (int j = 0; j < pages.length; j++) {
            // Create the model class for an intro Page.
        	if (!UAContentFilter.isFiltered(UAElementFactory.newElement(pages[j]), IntroEvaluationContext.getContext())) {
                IntroPage page = new IntroPage(pages[j], bundle, base);
                page.setParent(this);
                children.add(page);
        	}
        }
    }


    private void tryResolvingExtensions() {
    	int previousSize;
    	do {
    		previousSize = unresolvedConfigExt.size();
            Enumeration keys = unresolvedConfigExt.keys();
            while (keys.hasMoreElements()) {
                Element extensionContentElement = (Element) keys.nextElement();
                IConfigurationElement configExtElement = (IConfigurationElement) unresolvedConfigExt
				    .get(extensionContentElement);
				Bundle bundle = BundleUtil.getBundleFromConfigurationElement(configExtElement);
				String elementBase = getBase(configExtElement);
                processOneExtension(configExtElement, elementBase, bundle, extensionContentElement);

				if (!extensionContentElement.hasAttribute("failed")) { //$NON-NLS-1$
					
					unresolvedConfigExt.remove(extensionContentElement);
				}
            }
        } while (unresolvedConfigExt.size() < previousSize);
    }

    /**
     * load the extension content of this configExtension into model classes,
     * and insert them at target. A config extension can have only ONE extension
     * content. This is because if the extension fails, we need to be able to
     * not include the page and group contributions as part of the model. If
     * extension content has XHTML content (ie: content attribute is defined) we
     * load extension DOM into target page dom.
     * 
     * note: the extension Element is returned to enable creating a child model
     * element on failure.
     * 
     * @param
     * @return
     */
    private Element[] loadExtensionContent(Document dom,
            IConfigurationElement configExtElement, String base) {

        // get the bundle from the extensions since they are defined in
        // other plugins.
    	List elements = new ArrayList();
        Bundle bundle = BundleUtil
            .getBundleFromConfigurationElement(configExtElement);

        Element[] extensionContents = ModelUtil.getElementsByTagName(dom,
            IntroExtensionContent.TAG_CONTAINER_EXTENSION);
        if (extensionContents.length == 0) {
        	extensionContents = ModelUtil.getElementsByTagName(dom,
        			IntroExtensionContent.TAG_CONTAINER_REPLACE);
        }
        
        for (int i = 0; i < extensionContents.length; i++) {
        	Element extensionContentElement = extensionContents[i];
        	if (!UAContentFilter.isFiltered(UAElementFactory.newElement(extensionContentElement), IntroEvaluationContext.getContext())) {
        		 processOneExtension(configExtElement, base, bundle, extensionContentElement);
                 elements.add(extensionContentElement);
            }
        	
        }  
        return (Element[])elements.toArray(new Element[elements.size()]);
    }

	private void processOneExtension(IConfigurationElement configExtElement, String base, Bundle bundle,
			Element extensionContentElement) {
		// Create the model class for extension content.
		IntroExtensionContent extensionContent = new IntroExtensionContent(
		    extensionContentElement, bundle, base, configExtElement);
		boolean success = false;
		if (extensionContent.isXHTMLContent())
		    success = loadXHTMLExtensionContent(extensionContent);
		else
		    success = load3_0ExtensionContent(extensionContent);

		if (success) {
		    if (extensionContentElement.hasAttribute("failed")) //$NON-NLS-1$
		        extensionContentElement.removeAttribute("failed"); //$NON-NLS-1$
		} else
		    extensionContentElement.setAttribute("failed", "true"); //$NON-NLS-1$ //$NON-NLS-2$
	}


    /**
     * Insert the extension content into the target.
     * 
     * @param extensionContent
     * @return
     */
    private boolean loadXHTMLExtensionContent(
            IntroExtensionContent extensionContent) {
        String path = extensionContent.getPath();
        // path must be pageId/anchorID in the case of anchors in XHTML pages.
        String[] pathSegments = StringUtil.split(path, "/"); //$NON-NLS-1$
        if (pathSegments.length != 2)
            // path does not have correct format.
            return false;
        AbstractIntroPage targetPage = (AbstractIntroPage) findChild(
            pathSegments[0], ABSTRACT_PAGE);
        if (targetPage == null)
            // target could not be found. Signal failure.
            return false;

        // Insert all children of this extension before the target element. Anchors need
        // to stay in DOM, even after all extensions have been resolved, to enable other
        // plugins to contribute. Find the target node.
        Document pageDom = targetPage.getDocument();
        Element targetElement = targetPage.findDomChild(pathSegments[1], "*"); //$NON-NLS-1$
        if (targetElement == null)
            return false;

        // get extension content
        Element[] elements = extensionContent.getElements();
        // insert all children before anchor in page body.
        for (int i = 0; i < elements.length; i++) {
            Node targetNode = pageDom.importNode(elements[i], true);
            // update the src attribute of this node, if defined by w3
            // specs.

            ModelUtil.updateResourceAttributes((Element) targetNode,
                extensionContent);
            targetElement.getParentNode().insertBefore(targetNode, targetElement);
        }

        if (extensionContent.getExtensionType() == IntroExtensionContent.TYPE_REPLACEMENT) {
            targetElement.getParentNode().removeChild(targetElement);
        }
        
        // now handle style inheritance.
        // Update the parent page styles. skip style if it is null;
        String[] styles = extensionContent.getStyles();
        if (styles != null) {
            for (int i = 0; i < styles.length; i++)
                ModelUtil.insertStyle(pageDom, styles[i]);
        }

        return true;

    }



    /**
     * Insert the extension content (3.0 format) into the target.
     * 
     * @param extensionContent
     * @return
     */
    private boolean load3_0ExtensionContent(IntroExtensionContent extensionContent) {
        String path = extensionContent.getPath();
        int type = extensionContent.getExtensionType();
        AbstractIntroElement target = findTarget(this, path, extensionContent.getId());
        if (target != null && target.isOfType(AbstractIntroElement.ANCHOR) == (type == IntroExtensionContent.TYPE_CONTRIBUTION)) {
        	// insert all children of this extension before the target element/anchor.
	        insertExtensionChildren(target, extensionContent, extensionContent.getBundle(), extensionContent.getBase());
	        // anchors need to stay around to receive other contributions
	        if (type == IntroExtensionContent.TYPE_REPLACEMENT) {
	        	AbstractIntroContainer parent = (AbstractIntroContainer)target.getParent();
	        	parent.removeChild(target);
	        }
	        handleExtensionStyleInheritence(target, extensionContent);
	        return true;
        }
        // appropriate target could not be found. Signal failure.
        return false;
    }

    private void insertExtensionChildren(AbstractIntroElement target,
            IntroExtensionContent extensionContent, Bundle bundle, String base) {
        AbstractIntroContainer parent = (AbstractIntroContainer)target.getParent();
        // insert the elements of the extension before the target
        String mixinStyle = getMixinStyle(extensionContent);
        Element [] children = extensionContent.getChildren();
        parent.insertElementsBefore(children, bundle, base, target, mixinStyle);
    }
    
    private String getMixinStyle(IntroExtensionContent extensionContent) {
    	String path = extensionContent.getPath();
    	if (!path.endsWith("/@")) //$NON-NLS-1$
    		return null;
    	String pageId = path.substring(0, path.length()-2);
    	IntroModelRoot modelRoot = getModelRoot();
    	if (modelRoot==null)
    		return null;
    	IntroConfigurer configurer = modelRoot.getConfigurer();
    	if (configurer==null)
    		return null;
    	String extensionId = extensionContent.getId();
    	// if this is a replace, take the mixin style as what is being replaced
    	if (extensionContent.getExtensionType() == IntroExtensionContent.TYPE_REPLACEMENT) {
    		IPath ipath = new Path(extensionContent.getPath());
    		String s2 = ipath.segment(1);
    		if (s2 != null && s2.startsWith("@") && s2.length() > 1) { //$NON-NLS-1$
    			extensionId = s2.substring(1);
    		}
    	}
   		return configurer.getMixinStyle(pageId, extensionId);
    }


    /**
     * Updates the inherited styles based on the style attribtes defined in the
     * configExtension. If we are extending a shared group do nothing. For
     * inherited alt-styles, we have to cache the bundle from which we inherited
     * the styles to be able to access resources in that plugin.
     * 
     * @param include
     * @param target
     */
    private void handleExtensionStyleInheritence(AbstractIntroElement target,
            IntroExtensionContent extension) {

        AbstractIntroContainer targetContainer = (AbstractIntroContainer)target.getParent();
        if (targetContainer.getType() == AbstractIntroElement.GROUP
                && targetContainer.getParent().getType() == AbstractIntroElement.MODEL_ROOT)
            // if we are extending a shared group, defined under a config, we
            // can not include styles.
            return;

        // Update the parent page styles. skip style if it is null;
        String[] styles = extension.getStyles();
        if (styles != null)
            targetContainer.getParentPage().addStyles(styles);

        // for alt-style cache bundle for loading resources.
        Hashtable altStyles = extension.getAltStyles();
        if (altStyles != null)
            targetContainer.getParentPage().addAltStyles(altStyles);
    }

    /**
     * Sets the model state based on all the model classes. Dynamic nature of
     * the model is always setto false when we fail to load model for any
     * reason.
     */
    private void setModelState(boolean loaded, boolean hasValidConfig,
            boolean isdynamicIntro) {
        this.loaded = loaded;
        this.hasValidConfig = hasValidConfig;
        this.isdynamicIntro = isdynamicIntro;
    }

    /**
     * Returns true if there is a valid contribution to
     * org.eclipse.ui.intro.config extension point, with a valid Presentation,
     * and pages.
     * 
     * @return Returns the hasValidConfig.
     */
    public boolean hasValidConfig() {
        return hasValidConfig;
    }

    /**
     * @return Returns the introPartPresentation.
     */
    public IntroPartPresentation getPresentation() {
        return introPartPresentation;
    }
    
    public IntroConfigurer getConfigurer() {
    	return configurer;
    }

    /**
     * @return Returns the rootPage.
     */
    public IntroHomePage getHomePage() {
        return homePage;
    }

    /**
     * @return Returns the standby Page. May return null if standby page is not
     *         defined.
     */
    public IntroHomePage getStandbyPage() {
        return standbyPage;
    }

    /**
     * @return all pages *excluding* the Home Page. If all pages are needed,
     *         call <code>(AbstractIntroPage[])
     *         getChildrenOfType(IntroElement.ABSTRACT_PAGE);</code>
     */
    public IntroPage[] getPages() {
        return (IntroPage[]) getChildrenOfType(AbstractIntroElement.PAGE);
    }

    /**
     * @return Returns the isdynamicIntro.
     */
    public boolean isDynamic() {
        return isdynamicIntro;
    }

    /**
     * @return Returns the currentPageId.
     */
    public String getCurrentPageId() {
        return currentPageId;
    }


    /**
     * Sets the current page. If the model does not have a page with the passed
     * id, the message is logged, and the model retains its old current page.
     * 
     * @param currentPageId
     *            The currentPageId to set. *
     * @param fireEvent
     *            flag to indicate if event notification is needed.
     * @return true if the model has a page with the passed id, false otherwise.
     *         If the method fails, the current page remains the same as the
     *         last state.
     */
    public boolean setCurrentPageId(String pageId, boolean fireEvent) {
        if (pageId.equals(currentPageId))
            // setting to the same page does nothing. Return true because we did
            // not actually fail. just a no op.
            return true;

        AbstractIntroPage page = (AbstractIntroPage) findChild(pageId,
            ABSTRACT_PAGE);
        if (page == null) {
            // not a page. Test for root page.
            if (!pageId.equals(homePage.getId())) {
                // not a page nor the home page.
                Log
                    .warning("Could not set current page to Intro page with id: " + pageId); //$NON-NLS-1$
                return false;
            }
        }

        currentPageId = pageId;
        if (fireEvent)
            firePropertyChange(CURRENT_PAGE_PROPERTY_ID);
        return true;
    }

    public boolean setCurrentPageId(String pageId) {
        return setCurrentPageId(pageId, true);
    }

    public void addPropertyListener(IPropertyListener l) {
        propChangeListeners.add(l);
    }

    /**
     * Fires a property changed event. Made public because it can be used to
     * trigger a UI refresh.
     * 
     * @param propertyId
     *            the id of the property that changed
     */
    public void firePropertyChange(final int propertyId) {
        Object[] array = propChangeListeners.getListeners();
        for (int i = 0; i < array.length; i++) {
            final IPropertyListener l = (IPropertyListener) array[i];
            SafeRunner.run(new SafeRunnable() {

                public void run() {
                    l.propertyChanged(this, propertyId);
                }

                public void handleException(Throwable e) {
                    super.handleException(e);
                    // If an unexpected exception happens, remove it
                    // to make sure the workbench keeps running.
                    propChangeListeners.remove(l);
                }
            });
        }
    }

    public void removePropertyListener(IPropertyListener l) {
        propChangeListeners.remove(l);
    }

    /**
     * @return Returns the currentPage. return null if page is not found, or if
     *         we are not in a dynamic intro mode.
     */
    public AbstractIntroPage getCurrentPage() {
        if (!isdynamicIntro)
            return null;

        AbstractIntroPage page = (AbstractIntroPage) findChild(currentPageId,
            ABSTRACT_PAGE);
        if (page != null)
            return page;
        // not a page. Test for root page.
        if (currentPageId.equals(homePage.getId()))
            return homePage;
        // return null if page is not found.
        return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.internal.intro.impl.model.IntroElement#getType()
     */
    public int getType() {
        return AbstractIntroElement.MODEL_ROOT;
    }


    /**
     * Assumes that the passed config element has a "content" attribute. Reads
     * it and loads a DOM based on that attribute value. It does not explicitly
     * resolve the resource because this method only loads the introContent and
     * the configExt content files. ie: in plugin.xml. <br>
     * This method also sets the base attribute on the root element in the DOM
     * to enable resolving all resources relative to this DOM.
     * 
     * @return
     */
    protected Document loadDOM(IConfigurationElement cfgElement) {
        String content = cfgElement.getAttribute(ATT_CONTENT);

        // To support jarring, extract parent folder of where the intro content
        // file is. It is expected that all intro content is in that one parent
        // folder. This works for both content files and configExtension content
        // files.
        Bundle domBundle = BundleUtil
            .getBundleFromConfigurationElement(cfgElement);
        ModelUtil.ensureFileURLsExist(domBundle, content);

        // Resolve.
        content = BundleUtil.getResourceLocation(content, cfgElement);
        Document document = new IntroContentParser(content).getDocument();

        return document;
    }


    private String getBase(IConfigurationElement configElement) {
        String content = configElement.getAttribute(ATT_CONTENT);
        return ModelUtil.getParentFolderToString(content);
    }
    
    public String resolveVariables(String text) {
    	if (text==null) return null;
    	if (text.indexOf('$')== -1)
    		return text;
    	// resolve
    	boolean inVariable=false;
    	StringBuffer buf = new StringBuffer();
    	int vindex=0;
    	for (int i=0; i<text.length(); i++) {
    		char c = text.charAt(i);
    		if (c=='$') {
    			if (!inVariable) {
    				inVariable=true;
    				vindex=i+1;
    				continue;
    			}
 				inVariable=false;
   				String variable=text.substring(vindex, i);
   				String value = getVariableValue(variable);
   				if (value==null)
   					value = "$"+variable+"$"; //$NON-NLS-1$ //$NON-NLS-2$
   				buf.append(value);
   				continue;
    		}
    		else if (!inVariable)
    			buf.append(c);
    	}
    	return buf.toString();
    }

    private String getVariableValue(String variable) {
     	if (variable.equals(VAR_THEME)) {
    		if (theme!=null)
    			return theme.getPath();
    	}
    	if (configurer!=null)
    		return configurer.getVariable(variable);
    	return null;
    }
    
    public String resolvePath(String extensionId, String path) {
    	if (configurer==null) return null;
    	return configurer.resolvePath(extensionId, path);
    }

	
	public IntroTheme getTheme() {
		return theme;
	}

	public String getStartPageId() {
		return startPageId;
	}
}