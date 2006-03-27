/*******************************************************************************
 * Copyright (c) 2005 Intel Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Intel Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.help.internal.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.help.IHelpResource;
import org.eclipse.help.IIndexEntry;

/**
 * @author sturmash
 *
 * An internal implementation of index entry
 */
public class IndexEntry extends Index implements IIndexEntry {
    String keyword;
    List topics;
    
    public IndexEntry(Comparator comparator, String keyword) {
		this(comparator, keyword, new ArrayList());
	}

	public IndexEntry(Comparator comparator, String keyword, List topics) {
		super(comparator);
        this.keyword = keyword;
        this.topics = topics;
    }

	public IndexEntry(Comparator comparator, String keyword, List topics, List subentries) {
		super(comparator, subentries);
        this.keyword = keyword;
        this.topics = topics;
    }

	public void addTopic(String label, String href, String location) {
		topics.add(new IndexTopic(label, href, location));
	}
    /* (non-Javadoc)
     * @see org.eclipse.help.internal.index.IIndexEntry#getKeyword()
     */
    public String getKeyword() {
        return keyword;
    }
    
    /* (non-Javadoc)
     * @see org.eclipse.help.internal.index.IIndexEntry#getTopics()
     */
    public List getTopicList() {
        return topics;
    }

    public IHelpResource[] getTopics() {
    	if (topics == null)
    		return new IHelpResource[0];

    	IHelpResource topicArray[] = new IHelpResource[topics.size()];
    	topics.toArray(topicArray);
    	return topicArray;
    }

	public IIndexEntry[] getSubentries() {
		return getEntries();
	}
}
