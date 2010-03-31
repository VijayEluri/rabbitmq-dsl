package com.jbrisbin.groovy.mqdsl;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import groovy.lang.Closure;
import groovy.lang.GString;
import groovy.util.BuilderSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Created by IntelliJ IDEA. User: jbrisbin Date: Mar 31, 2010 Time: 10:16:03 AM To change this template use File |
 * Settings | File Templates.
 */
@SuppressWarnings({"unchecked"})
public class RabbitMQBuilder extends BuilderSupport {

  Logger log = LoggerFactory.getLogger(getClass());
  Channel channel;
  Exchange currentExchange;
  Queue currentQueue;
  ExecutorService pool = Executors.newCachedThreadPool();
  List<ClosureConsumer> closureConsumers = new ArrayList<ClosureConsumer>();
  BlockingQueue<Future<ClosureConsumer>> consumers = new LinkedBlockingQueue<Future<ClosureConsumer>>();
  Map<String, List<Closure>> eventHandlers = new LinkedHashMap<String, List<Closure>>();

  public Channel getChannel() {
    return channel;
  }

  public void setChannel(Channel channel) {
    this.channel = channel;
  }

  public BlockingQueue<Future<ClosureConsumer>> getConsumers() {
    return consumers;
  }

  public boolean isActive() {
    for (ClosureConsumer consumer : closureConsumers) {
      if (consumer.isActive()) {
        return true;
      }
    }
    return false;
  }

  public void cancelAllConsumers() {
    for (ClosureConsumer consumer : closureConsumers) {
      consumer.setActive(false);
    }
  }

  public void call(Closure cl) {
    cl.call(new Object[]{channel});
  }

  @Override
  protected void setParent(Object from, Object to) {
    if (log.isDebugEnabled()) {
      log.debug(String.format("setParent(Object o, Object o1): %s, %s", from, to));
    }
    if (from instanceof Exchange) {
      currentExchange = (Exchange) from;
    }
    if (from instanceof Queue) {
      currentQueue = (Queue) from;
    }
  }

  @Override
  protected Object createNode(Object o) {
    if (log.isDebugEnabled()) {
      log.debug(String.format("createNode(Object o): %s", o));
    }
    return null;
  }

  @Override
  protected Object createNode(Object o, Object o1) {
    if (log.isDebugEnabled()) {
      log.debug(String.format("setParent(Object o, Object o1): %s, %s", o, o1));
    }
    return null;
  }

  @Override
  protected Object createNode(Object o, final Map params) {
    if (log.isDebugEnabled()) {
      log.debug(String.format("createNode(Object o, Map params): %s, %s", o, params));
    }
    String node = o.toString();
    if (node.equals("on")) {
      // Event handlers
      for (Map.Entry<String, Closure> entry : ((Map<String, Closure>) params).entrySet()) {
        String eventName = entry.getKey();
        List<Closure> handlers;
        if (eventHandlers.containsKey(eventName)) {
          handlers = eventHandlers.get(eventName);
        } else {
          handlers = new ArrayList<Closure>();
          eventHandlers.put(eventName, handlers);
        }
        Closure cl = entry.getValue();
        cl.setProperty("name", eventName);
        handlers.add(cl);
      }
      return null;
    } else if (node.equals("exchange")) {
      Exchange exchange = new Exchange();
      currentExchange = exchange;
      if (params.containsKey("name")) {
        exchange.setName(params.get("name").toString());
      }
      exchange.setType((params.containsKey("type") ? params.get("type").toString() : "direct"));
      exchange.setDurable((params.containsKey("durable") ? (Boolean) params.get("durable") : false));
      exchange.setAutoDelete((params.containsKey("autoDelete") ? (Boolean) params.get("autoDelete") : false));
      exchange.setPassive((params.containsKey("passive") ? (Boolean) params.get("passive") : false));
      try {
        channel.exchangeDeclare(exchange.getName(),
            exchange.getType(),
            exchange.isPassive(),
            exchange.isDurable(),
            exchange.isAutoDelete(),
            exchange.getParameters());
      } catch (IOException e) {
        log.error(e.getMessage(), e);
        dispatchError(e);
      }
      return exchange;
    } else if (node.equals("queue")) {
      if (!params.containsKey("routingKey")) {
        throw new IllegalArgumentException(
            "You must specify the routing key used to bind this queue to the parent exchange.");
      }
      Queue q = new Queue();
      currentQueue = q;
      q.setDurable(params.containsKey("durable") ? (Boolean) params.get("durable") : false);
      q.setAutoDelete(params.containsKey("autoDelete") ? (Boolean) params.get("autoDelete") : true);
      q.setRoutingKey(params.containsKey("routingKey") ? params.get("routingKey").toString() : null);
      q.setPassive((params.containsKey("passive") ? (Boolean) params.get("passive") : false));
      q.setPassive((params.containsKey("exclusive") ? (Boolean) params.get("exclusive") : false));
      if (params.containsKey("name")) {
        try {
          if (null == params.get("name")) {
            q.setName(channel.queueDeclare().getQueue());
          } else {
            q.setName(params.get("name").toString());
            channel.queueDeclare(q.getName(),
                q.isPassive(),
                q.isDurable(),
                q.isExclusive(),
                q.isAutoDelete(),
                q.getParameters());
          }
          channel.queueBind(q.getName(), currentExchange.getName(), q.getRoutingKey());
        } catch (IOException e) {
          log.error(e.getMessage(), e);
          dispatchError(e);
        }
      }
      return q;
    } else if (node.equals("consume")) {
      if (params.containsKey("onmessage")) {
        final String tag = (params.containsKey("tag") ? params.get("tag").toString() : null);
        final boolean ack = (params.containsKey("ack") ? (Boolean) params.get("ack") : true);
        Object handler = params.get("onmessage");
        ClosureConsumer clConsumer = new ClosureConsumer();
        clConsumer.setParent(this);
        clConsumer.setAck(ack);
        clConsumer.setConsumerTag(tag);
        if (handler instanceof Closure) {
          clConsumer.addDelegate((Closure) handler);
        } else if ((handler instanceof String) || (handler instanceof GString)) {
          String eventName = handler.toString();
          if (eventHandlers.containsKey(eventName)) {
            for (Closure cl : eventHandlers.get(eventName)) {
              clConsumer.addDelegate(cl);
            }
          }
        }
        clConsumer.monitorQueue(channel, currentQueue.getName());
        consumers.add(pool.submit(clConsumer));
        closureConsumers.add(clConsumer);
        return clConsumer;
      }
      return this;
    } else if (node.equals("publish")) {
      AMQP.BasicProperties props = new AMQP.BasicProperties();
      Map<String, Object> headers = new LinkedHashMap<String, Object>();
      headers.putAll(params);
      props.setHeaders(headers);

      Message msg = new Message();
      msg.setProperties(props);

      if (params.containsKey("body")) {
        Object body = params.get("body");
        if (body instanceof Closure) {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          Closure cl = (Closure) body;
          cl.call(new Object[]{msg, out});
          try {
            out.flush();
          } catch (IOException e) {
            log.error(e.getMessage(), e);
            dispatchError(e);
          }
          msg.setBody(out.toByteArray());
        } else if (body instanceof String || body instanceof GString) {
          msg.setBody(body.toString().getBytes());
        } else {
          msg.setBody((byte[]) body);
        }
        headers.remove("body");

        String exchange = (null != currentExchange ? currentExchange.getName() : "");
        String routingKey = (null != currentQueue ? currentQueue.getRoutingKey() : "");
        try {
          if (eventHandlers.containsKey(Events.BEFORE_PUBLISH)) {
            dispatchEvent(Events.BEFORE_PUBLISH, new Object[]{exchange, routingKey, msg});
          }
          channel.basicPublish(exchange, routingKey, msg.getProperties(), msg.getBody());
          if (eventHandlers.containsKey(Events.AFTER_PUBLISH)) {
            dispatchEvent(Events.AFTER_PUBLISH, new Object[]{exchange, routingKey, msg});
          }
        } catch (IOException e) {
          log.error(e.getMessage(), e);
          dispatchError(e);
        }
      }
      return msg;
    } else {
      return null;
    }
  }

  @Override
  protected Object createNode(Object o, Map map, Object o1) {
    if (log.isDebugEnabled()) {
      log.debug(String.format("createNode(Object o, Map map, Object o1): %s, %s, %s", o, map, o1));
    }
    return null;
  }

  void dispatchError(Throwable t) {
    dispatchEvent("error", new Object[]{t});
  }

  void dispatchEvent(String name, Object[] args) {
    if (log.isDebugEnabled()) {
      log.debug("Dispatching event " + name + " with args: " + arrayToString(args));
    }
    if (eventHandlers.containsKey(name)) {
      for (Closure cl : eventHandlers.get(name)) {
        cl.call(args);
      }
    }
  }

  String arrayToString(Object[] o) {
    StringBuffer buff = new StringBuffer("[");
    for (int i = 0; i < o.length; i++) {
      if (i > 0) {
        buff.append(",");
      }
      buff.append(String.valueOf(o[i]));
    }
    buff.append("]");
    return buff.toString();
  }

}
