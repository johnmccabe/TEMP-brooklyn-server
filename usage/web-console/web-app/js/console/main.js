/* Common parts of the web console. Should be loaded first.
 * 
 * Components can be put in their own files.
 */

/* Everything should be kept in here.
   e.g. Brooklyn.tabs contains the tabs module.
 */
var Brooklyn = {};

/* Used to bind jQuery custom events for the application. */
Brooklyn.eventBus = {};

Brooklyn.main = (function() {
    /* A timer for periodically refreshing data we display. */
    var updateInterval = 5000;

    function triggerUpdateEvent () {
        $(Brooklyn.eventBus).trigger('update');
    }

    setInterval(triggerUpdateEvent, updateInterval);

    /* Display the status of the management console.
     *
     * This is meant to be used to tell the user that the data is updating live
     * or that there is some error fetching it.
     *
     * These status line updating functions are called on the update_ok and
     * update_failed events. This is to allow many UI components to
     * signal their status easily. It doesn't deal with the case of one
     * component failing and another working very shortly afterwards. We
     * should have a think about this more but it is probably ok to start with.
     */
    function updateOK() {
        $("#status-message").html(currentTimeAsString());
    }

    // Yes, this is stupid. I don't know of a standard way to do it though.
    function zeroPad(i) {
        return (i < 10) ? "0" + i : i;
    }

    function currentTimeAsString() {
        // TODO: I don't know of a standard way to format dates. There seem to be libraries for it though. Meh.
        var date = new Date();
        return zeroPad(date.getHours()) + ":" + zeroPad(date.getMinutes()) + ":" + zeroPad(date.getSeconds());
    }

    function updateFailed(e, message) {
        $("#status-message").html("Failed at " + currentTimeAsString() + " (" + (message ? message : "unknown error") + ")");
    }

    function handleBreadCrumbs(json){
        var newContent = '<ul>';
        for (var p = json.length - 1; p > 0; p--) {
            newContent += '<li><a href="#" onClick="Brooklyn.jsTree.selectNodeByEntityId(\'' + json[p] + '\')">' + json[p][1] + '</a></li>';
        }
        newContent += '<li>' + json[p][1] + '</li></ul>';
        document.getElementById('navigation').innerHTML = newContent;
    }

    /* This method is intended to be called as an event handler. The e paramater is
     * unused.
     */
    function getBreadCrumbs(e, id) {
        if (typeof id !== 'undefined') {
            $.getJSON("../entity/breadcrumbs?id=" + id, handleBreadCrumbs).error(
                function() {$(Brooklyn.eventBus).trigger('update_failed', "Could not get entity info to show in summary.");});
        }
    }

    function init() {
        $(Brooklyn.eventBus).bind("update_ok", updateOK);
        $(Brooklyn.eventBus).bind("update_failed", updateFailed);
        $(Brooklyn.eventBus).bind("entity_selected", getBreadCrumbs);
    }

    return {
        init: init
    };

})();

$(document).ready(Brooklyn.main.init);
