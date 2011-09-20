package brooklyn.enricher

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.entity.Entity
import brooklyn.event.Sensor
import brooklyn.event.SensorEvent
import brooklyn.policy.basic.BaseAggregatingEnricher

/**
 * Subscribes to events from producers with a sensor of type T, aggregates them with the 
 * provided closure and emits the result on the target sensor.
 * @param <T>
 */
class CustomAggregatingEnricher<T> extends BaseAggregatingEnricher<T>  {
    
    private static final Logger LOG = LoggerFactory.getLogger(CustomAggregatingEnricher.class)
    
    private Closure aggegator
    
    /**
     * @param aggregator Should take a list of values and return a single, aggregate value
     * @param defaultValue Default value to populate the list given to aggregator, defaults to null
     */
    public CustomAggregatingEnricher(List<Entity> producer, Sensor<T> source, Sensor<?> target, 
            Closure aggregator, T defaultValue=null) {
        super(producer, source, target, defaultValue)
        this.aggegator = aggregator
    }
    
    @Override
    public void onEvent(SensorEvent<T> event) {
        try {
            values.put(event.getSource(), event.getValue())
            entity.setAttribute(target, getAggregate())
        } catch (Throwable t) {
            t.printStackTrace();
            throw t
        }
    }
    
    public Object getAggregate() {
        return aggegator.call(values.values())
    }
    
    public static <R extends Number> CustomAggregatingEnricher<R> getSummingEnricher(
            List<Entity> producer, Sensor<R> source, Sensor<R> target) {
        return new CustomAggregatingEnricher<R>(producer, source, target, { it?.sum(0, {it ?: 0}) ?: 0 }, 0)
    }

    public static <R extends Number> CustomAggregatingEnricher<R> getAveragingEnricher(
            List<Entity> producer, Sensor<R> source, Sensor<Double> target) {
        return new CustomAggregatingEnricher<R>(producer, source, target, {
            (it == null || it.isEmpty()) ? 0d : (Double) it.sum(0, {it ?: 0}) / it.size()
        }, 0d)
    }

}
