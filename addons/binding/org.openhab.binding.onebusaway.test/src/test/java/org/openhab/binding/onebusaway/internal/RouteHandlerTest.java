package org.openhab.binding.onebusaway.internal;

import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.thing.*;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerCallback;
import org.eclipse.smarthome.core.thing.type.ChannelKind;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.openhab.binding.onebusaway.internal.handler.ObaStopArrivalResponse;
import org.openhab.binding.onebusaway.internal.handler.RouteHandler;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ScheduledFuture;

/**
 * Test clases for {@link org.openhab.binding.onebusaway.internal.handler.RouteHandler}
 */
public class RouteHandlerTest {

  @Mock
  Thing mockThing;

  @Mock
  ThingHandlerCallback thingHandlerCallback;

  @Mock
  Channel mockChannel;

  public RouteHandlerTest() {
    super();
    MockitoAnnotations.initMocks(this);
  }

  @Before
  public void resetMocks() {
    Mockito.reset(mockThing, thingHandlerCallback);

    Configuration mockConfiguration = new Configuration();

    Mockito.when(mockChannel.getKind()).thenReturn(ChannelKind.TRIGGER);
    Mockito.when(mockChannel.getUID()).thenReturn(new ChannelUID("OneBusAway:TestHub:TestThing:TestChannel"));
    Mockito.when(mockChannel.getConfiguration()).thenReturn(mockConfiguration);

    Mockito.when(mockThing.getConfiguration()).thenReturn(mockConfiguration);
    Mockito.when(mockThing.getChannels()).thenReturn(Arrays.asList(mockChannel));
    Mockito.when(mockThing.getChannel(Mockito.anyObject())).thenReturn(mockChannel);
  }

  @Test
  public void testInitialization() {
    RouteHandler routeHandler = new RouteHandler(mockThing);

    routeHandler.initialize();
    Assert.assertEquals(routeHandler.getThing(), mockThing);
  }

  /**
   * This test checks that updates from OBA are correctly queueued for future execution
   * and do NOT trigger immediate updates
   * @throws InterruptedException if the test is ended prematurely.
   */
  @Test
  public void testNewRouteDataNoCallback() throws InterruptedException {

    RouteHandler routeHandler = new RouteHandler(mockThing);
    routeHandler.initialize();
    routeHandler.setCallback(thingHandlerCallback);

    // Create a list of updates
    ObaStopArrivalResponse update = createUpdate(1, 1000, 0);

    // Create an event update
    routeHandler.onNewRouteData(
            System.currentTimeMillis() - 10000, // Last update was 10 seconds ago
            Arrays.asList(update.data.entry.arrivalsAndDepartures)
    );

    List<ScheduledFuture<?>> scheduledFutures = this.getScheduledFutures(routeHandler);
    Assert.assertEquals(1, scheduledFutures.size());

    Thread.sleep(200);

    // Check calls
    Mockito.verify(thingHandlerCallback, Mockito.times(1))
            .statusUpdated(Mockito.anyObject(), Mockito.anyObject());

    Mockito.verify(thingHandlerCallback, Mockito.times(0))
            .channelTriggered(Mockito.eq(mockThing), Mockito.anyObject(), Mockito.anyString());
  }

  /**
   * This test checks that updates from OBA are correctly queueued for future execution
   * and updates are triggered after time has passed.
   * The test generates
   * @throws InterruptedException if the test is ended prematurely.
   */
  @Test
  public void testNewRouteDataSingleCallback() throws InterruptedException {
    int numberOfUpdates = 5;

    RouteHandler routeHandler = new RouteHandler(mockThing);
    routeHandler.initialize();
    routeHandler.setCallback(thingHandlerCallback);
    ObaStopArrivalResponse update = createUpdate(numberOfUpdates, 100, 1);

    // Create an event update
    routeHandler.onNewRouteData(
            System.currentTimeMillis() - 10000, // Last update was 10 seconds ago
            Arrays.asList(update.data.entry.arrivalsAndDepartures)
    );

    List<ScheduledFuture<?>> scheduledFutures = this.getScheduledFutures(routeHandler);
    Assert.assertEquals(numberOfUpdates, scheduledFutures.size());

    Thread.sleep(200);

    // Check calls
    Mockito.verify(thingHandlerCallback, Mockito.times(1))
            .statusUpdated(Mockito.anyObject(), Mockito.anyObject());

    Mockito.verify(thingHandlerCallback, Mockito.times(numberOfUpdates))
            .channelTriggered(Mockito.eq(mockThing), Mockito.anyObject(), Mockito.anyString());
  }


  /**
   * This test checks that new updates properly cancel and remove old scheduled futures from previous updates
   * @throws InterruptedException if the test is ended prematurely.
   */
  @Test
  public void testNewDataCancelsFutures() throws InterruptedException {
    int numberOfUpdates = 5;

    RouteHandler routeHandler = new RouteHandler(mockThing);
    routeHandler.initialize();
    routeHandler.setCallback(thingHandlerCallback);

    // Begin first update
    // Create a first round of updates. Set them well into the future so they don't trigger
    ObaStopArrivalResponse firstUpdate = createUpdate(numberOfUpdates, 10000, 1);

    routeHandler.onNewRouteData(
            System.currentTimeMillis() - 10000, // Last update was 10 seconds ago
            Arrays.asList(firstUpdate.data.entry.arrivalsAndDepartures)
    );

    List<ScheduledFuture<?>> firstScheduledFutures = new LinkedList<>(this.getScheduledFutures(routeHandler));
    Assert.assertEquals(numberOfUpdates, firstScheduledFutures.size());

    Thread.sleep(200);
    // End first update

    // Begin second update
    // Create a first round of updates. Set them in a shorter span so they trigger
    ObaStopArrivalResponse secondUpdate = createUpdate(numberOfUpdates, 100, 1);

    routeHandler.onNewRouteData(
            System.currentTimeMillis() - 5000, // Last update was 5 seconds ago
            Arrays.asList(secondUpdate.data.entry.arrivalsAndDepartures)
    );

    List<ScheduledFuture<?>> secondScheduledFutures = new LinkedList<>(this.getScheduledFutures(routeHandler));
    Assert.assertEquals(numberOfUpdates, secondScheduledFutures.size());

    Thread.sleep(200);
    // End second update

    // Check that both sets of futures are different
    for(ScheduledFuture sf: firstScheduledFutures) {
      Assert.assertFalse(secondScheduledFutures.contains(sf));
    }
    for(ScheduledFuture sf: secondScheduledFutures) {
      Assert.assertFalse(firstScheduledFutures.contains(sf));
    }

    // Check that the first set of futures were canceled
    for(ScheduledFuture sf: firstScheduledFutures) {
      Assert.assertTrue(sf.isCancelled());
    }

    // Check that the second set of futures (and only the second set of futures) were triggered
    for(ScheduledFuture sf: secondScheduledFutures) {
      Assert.assertTrue(sf.isDone());
    }

    // Check calls
    Mockito.verify(thingHandlerCallback, Mockito.times(2))
            .statusUpdated(Mockito.anyObject(), Mockito.anyObject());

    Mockito.verify(thingHandlerCallback, Mockito.times(numberOfUpdates))
            .channelTriggered(Mockito.eq(mockThing), Mockito.anyObject(), Mockito.anyString());
  }


  /**
   * Generate a simplified OBA API response
   * @param numberOfUpdates how many updates are included in the response
   * @param baseDelay how much in the future the updates are scheduled
   * @param delayIncrement how much in the future each update is compared to the one before it
   * @return an ObaStopArrivalResponse object with a simplified OBA API response
   */
  private ObaStopArrivalResponse createUpdate(int numberOfUpdates, int baseDelay, int delayIncrement) {
    // Create a list of updates
    ObaStopArrivalResponse update = new ObaStopArrivalResponse();
    update.currentTime = System.currentTimeMillis();
    update.data = update.new Data();
    update.data.entry = update.new Entry();
    update.data.entry.arrivalsAndDepartures = new ObaStopArrivalResponse.ArrivalAndDeparture[numberOfUpdates];

    for(int i = 0; i < numberOfUpdates; i++) {
      ObaStopArrivalResponse.ArrivalAndDeparture arrival = update.new ArrivalAndDeparture();
      update.data.entry.arrivalsAndDepartures[i] = arrival;

      arrival.predictedArrivalTime = System.currentTimeMillis() + baseDelay + (i * delayIncrement);
      arrival.scheduledArrivalTime = arrival.predictedArrivalTime;

      arrival.routeId = "TestRoute";
    }

    return update;
  }

  /**
   * Retrieves the Scheduled Futures collection from the provided Route Handler
   * This is a hack in the absence of a proper getter for the
   * @param routeHandler
   * @return
   */
  private List<ScheduledFuture<?>> getScheduledFutures(RouteHandler routeHandler) {
    try {
      Field field = RouteHandler.class.getDeclaredField("scheduledFutures");
      field.setAccessible(true);
      return (List<ScheduledFuture<?>>) field.get(routeHandler);
    } catch (NoSuchFieldException e) {
      e.printStackTrace();
    } catch (IllegalAccessException e) {
      e.printStackTrace();
    }
    return null;
  }

}
