/*
 * (c) Copyright IBM Corp. 2000, 2002.
 * All Rights Reserved.
 */
 
// Common scripts for IE and Mozilla.

var isMozilla = navigator.userAgent.indexOf('Mozilla') != -1 && parseInt(navigator.appVersion.substring(0,1)) >= 5;
var isIE = navigator.userAgent.indexOf('MSIE') != -1;

// selected node
var active;
var oldActive;

/**
 * Returns the target node of an event
 */
function getTarget(e) {
	var target;
  	if (isMozilla)
  		target = e.target;
  	else if (isIE)
   		target = window.event.srcElement;

	return target;
}


/**
 * Returns the row of this click
 */
function getTRNode(node) {
  if (node.nodeType == 3)  //"Node.TEXT_NODE") 
	return node.parentNode.parentNode.parentNode;
  else if (node.tagName == "A")
  	return node.parentNode.parentNode;
  else if (node.tagName == "TD") 
    return node.parentNode;
  else if (node.tagName == "TR") 
    return node;
  else if (node.tagName == "IMG")
  	return node.parentNode.parentNode.parentNode;
  else
  	return null;
}

/**
 * Returns the anchor node in this row
 */
function getAnchorNode(tr)
{
	var id = tr.id.substring(1);
	return document.getElementById("a"+id);
}


/**
 * Return next item in the list
 */
function getNextDown(node)
{
	var tr = getTRNode(node);
	if (tr == null) return null;
	
	var id = tr.id.substring(1);
	var next = 1 + eval(id);
	return document.getElementById("a"+next);
}

/**
 * Return previous item in the list
 */
function getNextUp(node)
{
	var tr = getTRNode(node);
	if (tr == null) return null;
	
	var id = tr.id.substring(1);
	var next = eval(id) - 1;
	if (next >= 0)
		return document.getElementById("a"+next);
	else
		return null;
}


/**
 * Highlights link. Returns true if highlights.
 */
function highlightTopic(topic)
{
  	if (!topic || (topic.tagName != "A" && topic.parentNode.tagName != "A"))
		return false;
	
  	var tr = getTRNode(topic); 
  	if (tr != null)
  	{
  	   	if (oldActive && oldActive != tr) {
    		oldActive.className="list";
    		var oldA = getAnchorNode(oldActive);
    		if (oldA) oldA.className = "";
  	   	}
    
		oldActive = tr;		
  		tr.className = "active";
  		var a = getAnchorNode(tr);
  		if (a)
  		{
  			a.className = "active";
  			// set toolbar title
  			if (a.onclick)
  				a.onclick();
  			//if (isIE)
  			//	a.hideFocus = "true";
   		}
   		active = a;
   		return true;
  	}
  	return false;
}

/**
 * Selects a topic in the tree: expand tree and highlight it
 */
function selectTopic(topic) 
{
	if (!topic || topic == "") return;
	
	var links = document.getElementsByTagName("a");

	for (var i=0; i<links.length; i++)
	{
		// take into account the extra ?toc=.. or &toc=
		if (links[i].href.indexOf(topic+"?toc=") == 0 ||
			links[i].href.indexOf(topic+"&toc=") == 0 ||
			links[i].href.indexOf(topic+"/?toc=") == 0)
		{
			highlightTopic(links[i]);
			scrollIntoView(links[i]);
			links[i].scrollIntoView(true);
			return true;
		}
	}
	return false;
}

/**
 * Selects a topic in the list
 */
function selectTopicById(id)
{
	var topic = document.getElementById(id);
	if (topic)
	{
		highlightTopic(topic);
		scrollIntoView(topic);
		return true;
	}
	return false;
}


/**
 * Scrolls the page to show the specified element
 */
function scrollIntoView(node)
{
	var scroll = getVerticalScroll(node);
	if (scroll != 0)
		window.scrollBy(0, scroll);
}

/**
 * Scrolls the page to show the specified element
 */
function getVerticalScroll(node)
{
	// Use the parent element for getting the offsetTop, as it appears
	// that tables get their own layout measurements.

	//var nodeTop = node.offsetTop;
	var nodeTop = node.parentNode.offsetTop;
		
	//var nodeBottom = nodeTop + node.offsetHeight;
	var nodeBottom = nodeTop + node.parentNode.offsetHeight;
	
	var pageTop = 0;
	var pageBottom = 0;
		
	if (isIE)
	{
		pageTop = document.body.scrollTop; 
		pageBottom = pageTop + document.body.clientHeight;
	
	} 
	else if (isMozilla)
	{
		pageTop = window.pageYOffset;
		pageBottom = pageTop + window.innerHeight - node.offsetHeight;
	}
	
	var scroll = 0;
	if (nodeTop >= pageTop )
	{
		if (nodeBottom <= pageBottom)
			scroll = 0; // already in view
		else
			scroll = nodeBottom - pageBottom/2;
	}
	else
	{
		scroll = nodeTop - pageTop;
	}
	
	return scroll;
}

function hidePopupMenu() {
	// hide popup if open
	var menu = document.getElementById("menu");
	if (!menu)
		return;
	if (menu.style.display == "block")
		menu.style.display = "none";
}

function showPopupMenu(e) {
	// show the menu
	var x = e.clientX;
	var y = e.clientY;

	e.cancelBubble = true;

	var menu = document.getElementById("menu");
	if (!menu) 
		return;	
	menu.style.left = x+1;
	menu.style.top = y+1;
	menu.style.display = "block";
}


/**
 * Popup a menu on right click over a bookmark.
 * This handler assumes the list.js script has been loaded.
 */
function contextMenuHandler(e)
{
	// hide popup if open
	hidePopupMenu();

	if (isIE)
		e = window.event;
		
  	var clickedNode;
  	if (isMozilla)
  		clickedNode = e.target;
  	else if (isIE)
   		clickedNode = e.srcElement;

  	if (!clickedNode)
  		return true;
  	
  	// call the click handler to select node
  	mouseClickHandler(e);
  	
  	if(clickedNode.tagName == "A")
  		active = clickedNode;
  	else if (clickedNode.parentNode.tagName == "A")
  		active = clickedNode.parentNode;
  	else
  		return true;
	
	showPopupMenu(e);
	
	return false;
}


/**
 * display topic label in the status line on mouse over topic
 */
function mouseMoveHandler(e) {
	try{
	  var overNode;
	  if (isMozilla)
	  	overNode = e.target;
	  else if (isIE)
	   	overNode = window.event.srcElement;
	  else 
	  	return;
	  	
	  overNode = getTRNode(overNode);
	  if (overNode == null)
	   return;
	 
	  if (isMozilla)
	     e.cancelBubble = false;
	   
	  if (isIE)  
		  window.status = getAnchorNode(overNode).innerText;
	  else if (isMozilla)
	  	  window.status = getAnchorNode(overNode).lastChild.nodeValue;
	}catch(e){}
	
	return true;
}

/**
 * handler for clicking on a node
 */
function mouseClickHandler(e) {

	// hide popup if open
	var menu = document.getElementById("menu");
	if (menu && menu.style.display == "block")
		menu.style.display = "none";
		
  	var clickedNode;
 	if (isMozilla)
  		clickedNode = e.target;
  	else if (isIE)
   		clickedNode = window.event.srcElement;
  	else 
  		return true;
  	
  	highlightTopic(clickedNode);
}


function focusHandler(e)
{
	if (oldActive)
		oldActive.focus();
}

/**
 * Handler for key down (arrows)
 */
function keyDownHandler(e)
{
	var key;
	var altKey;
	var shiftKey;
	var ctrlKey;
	
	if (isIE) {
		key = window.event.keyCode;
		altKey = window.event.altKey;
		shiftKey = window.event.shiftKey;
		ctrlKey = window.event.ctrlKey;
	} else if (isMozilla) {
		key = e.keyCode;
		altKey = e.altKey;
		shiftKey = e.shiftKey;
		ctrlKey = e.ctrlKey;
	}
		
	if (key == 9 || key == 13 || altKey || shiftKey || ctrlKey ) // tab, enter or modifiers
		return true;
	if (isMozilla)
  		e.cancelBubble = true;
  	else if (isIE)
  		window.event.cancelBubble = true;
  		
  	if (key == 40 ) { // down arrow
  		var clickedNode = getTarget(e);
  		if (!clickedNode) return;

		var next = getNextDown(clickedNode);
		if (next)
			next.focus();

  	} else if (key == 38 ) { // up arrow
  		var clickedNode = getTarget(e);
  		if (!clickedNode) return;

		var next = getNextUp(clickedNode);
		if (next)
			next.focus();
  	}
  				
  	return false;
}


// listen for events
if (isMozilla) {
  document.addEventListener('click', mouseClickHandler, true);
  document.addEventListener('mouseover', mouseMoveHandler, true);
  document.addEventListener('keydown', keyDownHandler, true);
  //document.addEventListener("focus", focusHandler, true);
}
else if (isIE){
  document.onclick = mouseClickHandler;
  document.onmouseover = mouseMoveHandler;
  document.onkeydown = keyDownHandler;
  window.onfocus = focusHandler;
}
