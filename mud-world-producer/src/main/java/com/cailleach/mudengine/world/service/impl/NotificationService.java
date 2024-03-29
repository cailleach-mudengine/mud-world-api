package com.cailleach.mudengine.world.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.apache.activemq.command.ActiveMQTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.cailleach.mudengine.common.security.Session;
import com.cailleach.mudengine.common.utils.CommonConstants;
import com.cailleach.mudengine.common.utils.NotificationMessage;
import com.cailleach.mudengine.common.utils.NotificationMessage.EnumEntity;
import com.cailleach.mudengine.common.utils.NotificationMessage.EnumNotificationEvent;
import com.cailleach.mudengine.world.model.PlaceEntity;
import com.cailleach.mudengine.world.model.PlaceExitEntity;
import com.cailleach.mudengine.world.rest.dto.PlaceExit;

import jakarta.annotation.PostConstruct;

@Component
public class NotificationService {
	
	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
	
	public static final String PLACE_DESTROY_MSG = "place.destroy";
	public static final String PLACE_CLASS_CHANGE_MSG = "place.class.change";
	
	public static final String PLACE_EXIT_CREATE_MSG = "place.exit.create";
	public static final String PLACE_EXIT_OPEN_MSG = "place.exit.open";
	public static final String PLACE_EXIT_CLOSE_MSG = "place.exit.close";
	public static final String PLACE_EXIT_LOCK_MSG = "place.exit.lock";
	public static final String PLACE_EXIT_UNLOCK_MSG = "place.exit.unlock";

	
	private JmsTemplate jmsTemplate;
	
	@Value("${place.topic:disabled}")
	private String placeTopicName;
	
	private ActiveMQTopic placeTopic;
	
	public NotificationService(JmsTemplate jmsTemplate) {
		this.jmsTemplate = jmsTemplate;
	}

	@PostConstruct
	public void setup() {
		placeTopic = new ActiveMQTopic(placeTopicName);
	}
	
	public List<NotificationMessage> handlePlaceDestroy(PlaceEntity destroyedPlace) {
		
		// Prepare a notification for this change
		NotificationMessage placeNotification = NotificationMessage.builder()
				// Who?
				.entity(NotificationMessage.EnumEntity.PLACE)
				.entityId(destroyedPlace.getCode())
				// What happened?
				.event(EnumNotificationEvent.PLACE_DESTROY)
				// Spread the news!
				.messageKey(PLACE_DESTROY_MSG)
				.args(new String[] {
						destroyedPlace.getName()!=null ? destroyedPlace.getName() : destroyedPlace.getPlaceClass().getName()
						})
				// The guys in the place will take interest on that  :D
				.targetEntity(EnumEntity.PLACE)
				.targetEntityId(destroyedPlace.getCode())
				.worldName(getWorldName())
			.build();
		
		
		return List.of(placeNotification);
	}
	
	public List<NotificationMessage> handlePlaceChange(PlaceEntity beforePlace, PlaceEntity afterPlace) {
		
		List<NotificationMessage> notifications = new ArrayList<>();
		
		// Comparing before and after items
		
		// Looking for placeClass changes
		checkPlaceClassChanges(beforePlace, afterPlace, notifications);
		
		// Looking for newly-created exits
		checkNewlyCreatedExits(beforePlace, afterPlace, notifications);
		
		// Looking for updated exits
		checkUpdatedExits(beforePlace, afterPlace, notifications);
		
		return notifications;
	}
	
	public void dispatchNotifications(List<NotificationMessage> notifications) {
		
		notifications.stream()
		.forEach(placeNotification -> {
			
			// Send the notification
			jmsTemplate.convertAndSend(placeTopic, placeNotification, m -> {
				
				if (SecurityContextHolder.getContext().getAuthentication()!=null) {
				
					m.setObjectProperty(CommonConstants.AUTH_TOKEN_HEADER, 
							SecurityContextHolder.getContext().getAuthentication().getCredentials());
				}
				
				return m;
			});
			
			log.info("world: {}, entityId: {}, event: {}",
					placeNotification.getWorldName(),
					placeNotification.getEntityId(),
					placeNotification.getEvent()
					);

		});
		
	}
	
	/**
	 * Compare place class changes and build the corresponding notification object.
	 * 
	 * @param beforePlace - current state of the MudPlace object in database
	 * @param afterPlace - future state of the MudPlace object
	 */
	private void checkPlaceClassChanges(PlaceEntity beforePlace, PlaceEntity afterPlace, List<NotificationMessage> notifications) {
		
		if (!beforePlace.getPlaceClass().getCode().equals(afterPlace.getPlaceClass().getCode())) {

			// Prepare a notification for this change
			NotificationMessage placeNotification = NotificationMessage.builder()
					// Who?
					.entity(NotificationMessage.EnumEntity.PLACE)
					.entityId(afterPlace.getCode())
					// What happened?
					.event(EnumNotificationEvent.PLACE_CLASS_CHANGE)
					// Spread the news!
					.messageKey(PLACE_CLASS_CHANGE_MSG)
					.args(new String[] {
							beforePlace.getName()!=null ? beforePlace.getName() : beforePlace.getPlaceClass().getName(),
							afterPlace.getPlaceClass().getName()
							})
					// The guys in the place will take interest on that
					.targetEntity(EnumEntity.PLACE)
					.targetEntityId(afterPlace.getCode())
					.worldName(getWorldName())
				.build();
			
			// Enqueue the notification
			notifications.add(placeNotification);
		}
	}
	
	/**
	 * Look at any newly created exits and build the corresponding notification object.
	 * 
	 * @param beforePlace - current state of the MudPlace object in database
	 * @param afterPlace - future state of the MudPlace object
	 */
	private void checkNewlyCreatedExits(PlaceEntity beforePlace, PlaceEntity afterPlace, List<NotificationMessage> notifications) {
		
		afterPlace.getExits().stream()
			.filter(d -> !beforePlace.getExits().contains(d))
			.forEach(d -> {
				
				// Create exit notification
				NotificationMessage placeNotification = NotificationMessage.builder()
						// Who?
						.entity(NotificationMessage.EnumEntity.PLACE)
						.entityId(afterPlace.getCode())
						// What happened?
						.event(EnumNotificationEvent.PLACE_EXIT_CREATE)
						// Spread the news!
						.messageKey(PLACE_EXIT_CREATE_MSG)
						.args(new String[] {
								PlaceExit.getOpposedDirection(d.getPk().getDirection())
								})
						// The guys in the place will take interest on that
						.targetEntity(EnumEntity.PLACE)
						.targetEntityId(afterPlace.getCode())
						.worldName(getWorldName())
					.build();

				// Enqueue the notification
				notifications.add(placeNotification);
				
				// Create exit notification for the other side
				NotificationMessage otherPlaceNotification = NotificationMessage.builder()
						// Who?
						.entity(NotificationMessage.EnumEntity.PLACE)
						.entityId(d.getTargetPlaceCode())
						// What happened?
						.event(EnumNotificationEvent.PLACE_EXIT_CREATE)
						// Spread the news!
						.messageKey(PLACE_EXIT_CREATE_MSG)
						.args(new String[] {
								d.getPk().getDirection()
								})
						// The guys in the place will take interest on that
						.targetEntity(EnumEntity.PLACE)
						.targetEntityId(d.getTargetPlaceCode())
						.worldName(getWorldName())
					.build();

				// Enqueue the notification too
				notifications.add(otherPlaceNotification);
				
			});
		
	}
	
	/**
	 * Traverse all exits found both in current and future state Place object.
	 * Check changes in each one of them (through checkOneUpdatedExit method).
	 * 
	 * @param beforePlace - current state of the MudPlace object in database
	 * @param afterPlace - future state of the MudPlace object
	 */
	private void checkUpdatedExits(PlaceEntity beforePlace, PlaceEntity afterPlace, List<NotificationMessage> notifications) {

		// Looking for exit changes
		beforePlace.getExits().stream()
			.filter(d -> afterPlace.getExits().contains(d))
			.forEach(beforeExit -> 
				
				// Search the afterExit
				afterPlace.getExits().stream()
					.filter(afterExit -> afterExit.equals(beforeExit))
					.findFirst()
					.ifPresent(afterExit -> 
						checkOneUpdatedExit(afterPlace.getCode(), beforeExit, afterExit, notifications)
					)
			);
	}
	

	/**
	 * Check one specific exit for changes and build the corresponding notification object.
	 * 
	 * Fields which change trigger a notification:
	 * - opened
	 * - locked
	 *
	 * @param placeCode - code of the place (used in notifications)
	 * @param beforeExit - current state of the exit.
	 * @param afterExit - future state of the exit.
	 */
	private void checkOneUpdatedExit(Long placeCode, PlaceExitEntity beforeExit, PlaceExitEntity afterExit, List<NotificationMessage> notifications) {
		
		if (beforeExit.isOpened() && !afterExit.isOpened()) {

			// send place.exit.close notification
			sendExitChangeNotification(placeCode, beforeExit.getDirection(), 
					NotificationMessage.EnumNotificationEvent.PLACE_EXIT_CLOSE, 
					PLACE_EXIT_CLOSE_MSG, notifications);
		}
		
		if (!beforeExit.isOpened() && afterExit.isOpened()) {
			
			// send place.exit.open notification
			sendExitChangeNotification(placeCode, beforeExit.getDirection(), 
					NotificationMessage.EnumNotificationEvent.PLACE_EXIT_OPEN, 
					PLACE_EXIT_OPEN_MSG, notifications);
		}
		
		if (beforeExit.isLocked() && !afterExit.isLocked()) {

			// send place.exit.unlock notification
			sendExitChangeNotification(placeCode, beforeExit.getDirection(), 
					NotificationMessage.EnumNotificationEvent.PLACE_EXIT_UNLOCK, 
					PLACE_EXIT_UNLOCK_MSG, notifications);
		}
		
		if (!beforeExit.isLocked() && afterExit.isLocked()) {

			// send place.exit.lock notification
			sendExitChangeNotification(placeCode, beforeExit.getDirection(), 
					NotificationMessage.EnumNotificationEvent.PLACE_EXIT_LOCK, 
					PLACE_EXIT_LOCK_MSG, notifications);
			
		}
	}


	/**
	 * Helper method to send a notification for any exit change.
	 * 
	 * @param placeId - place where the event happens
	 * @param direction - direction of the affected exit
	 * @param event - code of the event as defined in NotificationMessage.EnumNotificationEvent enumeration
	 * @param messageKey - message to be presented for this event as defined in WorldHelper
	 * @return
	 */
	private void sendExitChangeNotification(Long placeId, String direction, NotificationMessage.EnumNotificationEvent event, String messageKey, List<NotificationMessage> notifications) {

		NotificationMessage placeNotification = NotificationMessage.builder()
				// Who?
				.entity(NotificationMessage.EnumEntity.PLACE)
				.entityId(placeId)
				// What happened?
				.event(event)
				// Spread the news!
				.messageKey(messageKey)
				.args(new String[] {direction})
				// The guys in the place will take interest on that
				.worldName(getWorldName())
			.build();
		
		// Enqueue the notification
		notifications.add(placeNotification);
	}

	

	private String getWorldName() {

		try {
			Session sessionData = (Session)SecurityContextHolder.getContext().getAuthentication().getDetails();
		
			return sessionData.getCurWorldName();
		} catch(RuntimeException e) {
			return null;
		}
	}
}
