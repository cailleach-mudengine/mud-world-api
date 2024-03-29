package com.cailleach.mudengine.world.service.impl;

import java.util.HashSet;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cailleach.mudengine.common.exception.EntityNotFoundException;
import com.cailleach.mudengine.common.exception.IllegalParameterException;
import com.cailleach.mudengine.world.model.PlaceEntity;
import com.cailleach.mudengine.world.model.PlaceAttrEntity;
import com.cailleach.mudengine.world.model.PlaceClassEntity;
import com.cailleach.mudengine.world.model.PlaceExitEntity;
import com.cailleach.mudengine.world.repository.PlaceClassRepository;
import com.cailleach.mudengine.world.repository.PlaceRepository;
import com.cailleach.mudengine.world.rest.dto.Place;
import com.cailleach.mudengine.world.rest.dto.PlaceExit;
import com.cailleach.mudengine.world.service.PlaceService;
import com.cailleach.mudengine.world.service.converter.todb.PlaceAttrEntityConverter;
import com.cailleach.mudengine.world.service.converter.todb.PlaceExitEntityConverter;
import com.cailleach.mudengine.world.service.converter.todto.PlaceConverter;
import com.cailleach.mudengine.common.utils.LocalizedMessages;
import com.cailleach.mudengine.world.util.WorldHelper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PlaceServiceImpl implements PlaceService {

	private final PlaceRepository placeRepository;

	private final PlaceClassRepository placeClassRepository;

	@Override
	public Place getPlace(Long placeId) {
		
		return placeRepository
				.findById(placeId)
				.map(PlaceConverter::convert)
				.map(this::updateExitNames)
				.orElseThrow(() -> new EntityNotFoundException(LocalizedMessages.PLACE_NOT_FOUND, placeId));
	}
	
	private Place updateExitNames(Place originalPlace) {
		
		originalPlace.getExits().keySet().stream()
			.forEach(curDirection -> {
				
				PlaceExit curExit = originalPlace.getExits().get(curDirection);
				
				curExit.setName(
						placeRepository.findById(curExit.getTargetPlaceCode())
						.map(d -> d.getPlaceClass().getName())
						.orElse(null)
						);
				
			});
		
		return originalPlace;
	}

	
	@Override
	public Place updatePlace(Long placeId, Place requestPlace) {
		
		Place response = null;
		
		PlaceEntity dbPlace = placeRepository
				.findById(placeId)
				.orElseThrow(() -> new EntityNotFoundException(LocalizedMessages.PLACE_NOT_FOUND, placeId));
		
		
		// 1.. Check place attributes
		// ============================================
		internalSyncAttr(dbPlace, requestPlace);
		
		
		// 2.. Check place HP
		// ============================================
		boolean placeToBeDestroyed = internalSyncPlaceHealth(dbPlace, requestPlace);
		
		if (placeToBeDestroyed) {
			
			// destroy the place
			destroyPlace(dbPlace.getCode());

			// Retrieve it again from the database.
			response = getPlace(placeId);
			
		} else {

			// 3.. Check place class
			// ============================================
			
			// if placeClass is changed, resync the attributes of changed place
			if (!dbPlace.getPlaceClass().getCode().equals(requestPlace.getClassCode())) {
	
				// change placeClass			
				internalUpdateClass(dbPlace, requestPlace.getClassCode());
				
			}
			
			// 4.. Check place exits
			// ============================================
			
			internalSyncExits(dbPlace, requestPlace);
	
			// updating the place in database
			// Mounting the response
			response = PlaceConverter.convert(
					placeRepository.save(dbPlace)
					);
		}
		
		return response;
	}
	
	/**
	 * Check place health attribute and determine when a place is about to be destroyed.
	 * 
	 * This check is performed since the current database place has a MAXHP attribute.
	 * If that happens, the HP attribute is checked in the service request.
	 * 
	 * If the HP attribute value is higher than the MAXHP attribute, the HP will be updated to the maximum allowed.
	 * If the HP attribute value is lower than zero, the place is marked for destruction
	 * 
	 * If the HP attribute is not found in service request, it's considered zero (0).
	 * 
	 * 
	 * @param dbPlace - database representation of the place object
	 * @param requestPlace - service request
	 * @return
	 */
	private boolean internalSyncPlaceHealth(final PlaceEntity dbPlace, final Place requestPlace) {

		boolean placeDestroyed = false;
		
		// Check current place health
		// First, we obtain the maxHP for this place
		// if this value is different from zero, it means that this is a place that can be destroyed
		Integer maxHP = 
				dbPlace.getAttrs().stream()
					.filter(d-> d.getId().getCode().equals(WorldHelper.PLACE_MAX_HP_ATTR))
					.mapToInt(PlaceAttrEntity::getValue)
					.findFirst()
					.orElse(0);
		
		// Retrieve the current HP of the place.  That value came from the request
		Integer currentHP = requestPlace.getAttrs().getOrDefault(WorldHelper.PLACE_HP_ATTR, 0);
		
		// If the currentPlace has an HP and it is exhausted		
		placeDestroyed = (maxHP!=0) && (currentHP <=0);
		
		if ((maxHP!=0) && (currentHP > maxHP)) {
			
			// Adjusts the currentHP to the maximum			
			dbPlace.getAttrs().stream()
				.filter(d -> d.getId().getCode().equals(WorldHelper.PLACE_HP_ATTR))
				.findFirst()
				.ifPresent(e -> e.setValue(maxHP));
		}
		
		return placeDestroyed;
	}
	
	/**
	 * Sync the place attributes according to placeClass changes.
	 * Attributes found in previous class and NOT in the new place class will be removed.
	 * Attributes in the new place class that doesn't exist in current place will be added.
	 * Attributes in the new place class that EXISTS in the current place will be updated.
	 * 
	 * @param dbPlace - database record representing the place object
	 * @param previousPlaceClass - previous place class (null during place creation)
	 * @param placeClass - new place class to be applied
	 * @return
	 */
	private PlaceEntity internalSyncAttr(PlaceEntity dbPlace, PlaceClassEntity previousPlaceClass, PlaceClassEntity placeClass) {
		
		if (previousPlaceClass!=null) {
			
			// Check all the attributes that existed in old class
			// and not exists in the new one
			
			dbPlace.getAttrs().removeIf(d -> {
				
				boolean existsInOldClass = previousPlaceClass.getAttrs().stream()
						.anyMatch(e -> e.getCode().equals(d.getCode()));
				
				boolean existsInNewClass = placeClass.getAttrs().stream()
						.anyMatch(e -> e.getCode().equals(d.getCode()));
			
				return existsInOldClass && !existsInNewClass;
			});
		}
		
		// Looking for attributes to add/update
		placeClass.getAttrs().stream()
			.forEach(curClassAttr -> {
				
				PlaceAttrEntity dbAttr = 
					dbPlace.getAttrs().stream()
						.filter(e -> e.getCode().equals(curClassAttr.getCode()))
						.findFirst()
						.orElse(PlaceAttrEntityConverter.convert(dbPlace.getCode(), curClassAttr));
				
				// Set the value regardless if the attr came from existing
				// list or was created now
				dbAttr.setValue(curClassAttr.getValue());
				
				// Update the attribute list in entity
				dbPlace.getAttrs().add(dbAttr);
		});

		return dbPlace;
	}
	
	/**
	 * Sync place attributes between the database record and the service request.
	 * Any attributes in database record not found in the request will be removed.
	 * Attributes non existent in database record but in request will be added.
	 * Attributes found in both database and request will be updated.
	 * 
	 * Note: NO persist operation will be performed by this operation
	 * (it only works on entity)
	 * 
	 * @param dbPlace - database record for the place
	 * @param requestPlace - service request place
	 * @return
	 */
	private PlaceEntity internalSyncAttr(final PlaceEntity dbPlace, final Place requestPlace) {
		
		// Looking for attributes to remove
		Set<PlaceAttrEntity> filteredSet =
			dbPlace.getAttrs().stream()
				// Filtering all database attributes...
				.filter(db -> requestPlace.getAttrs().keySet().stream()
						// ... that isn't in the request
						.noneMatch(req -> req.equals(db.getId().getCode())))
				.collect(Collectors.toSet());

		
		dbPlace.getAttrs().removeAll(filteredSet);
			

		// Looking for attributes to add/update
		for(String curAttr: requestPlace.getAttrs().keySet()) {
			
			Integer curValue = requestPlace.getAttrs().get(curAttr);
			
			// Looking for existing attribute in db record list
			Optional<PlaceAttrEntity> foundAttr = 
				dbPlace.getAttrs().stream()
					.filter(a -> a.getId().getCode().equals(curAttr))
					.findFirst();

			// If the value exists in db record
			if (foundAttr.isPresent()) {
				
				// Updates the value of existing attribute
				foundAttr.get().setValue(curValue);
			} else {
				
				// Creates a new attribute
				dbPlace.getAttrs().add(
						PlaceAttrEntityConverter.build(dbPlace.getCode(), curAttr, curValue)
						);
			}
		}

		return dbPlace;
	}
	
	private PlaceEntity internalSyncExits(PlaceEntity dbPlace, Place requestPlace) {
		
		// 4. exits		
		if (requestPlace.getExits()!=null) {
			
			Set<PlaceExitEntity> newExits = new HashSet<>();
			
			requestPlace.getExits().keySet().stream()
				.forEach(curDirection -> {

				// Retrieve the exit from the request
				PlaceExit curRequestExit = requestPlace.getExits().get(curDirection);
					
				// Search the exit in current db record
				PlaceExitEntity dbExit = 
					dbPlace.getExits().stream()
						.filter(e -> e.getPk().getDirection().equals(curDirection))
						.findFirst()
						.orElseGet(()-> 
							PlaceExitEntityConverter.build(curRequestExit, dbPlace.getCode(), curDirection)
						);
				
				
				// Update the record with request information
				dbExit.setVisible(curRequestExit.isVisible());
				dbExit.setOpened(curRequestExit.isOpened());
				dbExit.setLocked(curRequestExit.isLocked());
				
				// Add the updated exit at list
				newExits.add(dbExit);
				
			});
			
			// As hibernate manages the child list returned by him, we must not to create
			// a new list, but to clear the existing one to force DELETE/UPDATE of changed entries
			dbPlace.getExits().clear();
			dbPlace.getExits().addAll(newExits);
			
		}
		
		return dbPlace;
		
	}
	
	/**
	 * This method changes a place class.
	 * A change like this implies in resync the current place attributes with
	 * the ones in the new place class.
	 * 
	 * This method is called from:
	 * - during destroyPlace flow if the current place class has a designed demised place class
	 * - during updatePlace flow if the current place class is changed for any reason
	 * 
	 * @param original - current place object
	 * @param newPlaceClassCode - code of the new placeClass
	 * @return
	 */
	private PlaceEntity internalUpdateClass(PlaceEntity original, String newPlaceClassCode) {
		
		PlaceClassEntity placeClass = placeClassRepository
				.findById(newPlaceClassCode)
				.orElseThrow(() -> new EntityNotFoundException(LocalizedMessages.PLACE_CLASS_NOT_FOUND, newPlaceClassCode));

		internalSyncAttr(original, original.getPlaceClass(), placeClass);
		original.setPlaceClass(placeClass);
		
		return original;
	}


	@Override
	public void destroyPlace(Long placeId) {
		
		PlaceEntity dbPlace = placeRepository
				.findById(placeId)
				.orElseThrow(() -> new EntityNotFoundException(LocalizedMessages.PLACE_NOT_FOUND, placeId));

		// If exists a demise place class for this location
		if (dbPlace.getPlaceClass().getDemisedPlaceClassCode()!=null) {

			// Change the placeClass to the demised one
			internalUpdateClass(dbPlace, dbPlace.getPlaceClass().getDemisedPlaceClassCode());
			
			placeRepository.save(dbPlace);
			
		} else {
			
			// Destroy the place
			placeRepository.deleteById(dbPlace.getCode());
		}
	}


	@Override
	public Place createPlace(String placeClassCode, String direction, Long targetPlaceCode) {
		
		// Retrieving the placeClass
		PlaceClassEntity dbPlaceClass = placeClassRepository
				.findById(placeClassCode)
				.orElseThrow(() -> new EntityNotFoundException(LocalizedMessages.PLACE_CLASS_NOT_FOUND, placeClassCode));
		
		// Retrieving the targetPlace
		PlaceEntity targetDbPlace = placeRepository
				.findById(targetPlaceCode)
				.orElseThrow(() -> new EntityNotFoundException(LocalizedMessages.PLACE_NOT_FOUND, targetPlaceCode));
		
		// Check the corresponding exit of target place to be update in this flow
		String correspondingDirection = PlaceExit.getOpposedDirection(direction);

		// Check if the target place already has an exit to this direction
		if (targetDbPlace.getExits().stream()
			.anyMatch(d -> d.getPk().getDirection().equals(correspondingDirection))) {
			
			throw new IllegalParameterException(LocalizedMessages.PLACE_EXIT_EXISTS);
		}
		
		PlaceEntity newPlace = new PlaceEntity();
		newPlace.setPlaceClass(dbPlaceClass);

		// Saving in database with minimum information in order to have the placeId
		PlaceEntity dbPlace = placeRepository.save(newPlace);
		
		// Updating attributes based on PlaceClass attributes
		internalSyncAttr(dbPlace, null, dbPlaceClass);

		// Creating the exit for the new place
		dbPlace.getExits().add(
				PlaceExitEntityConverter.build(
						dbPlace.getCode(), 
						direction, 
						targetPlaceCode)
				);
		
		// Updating the new place in database
		dbPlace = placeRepository.save(dbPlace);
		
		// Updating the targetPlace exit to have a corresponding exit to new place created
		PlaceExitEntity correspondingExit = PlaceExitEntityConverter.build(
				targetDbPlace.getCode(), 
				correspondingDirection, 
				dbPlace.getCode());
		
		targetDbPlace.getExits().add(correspondingExit);
		placeRepository.save(targetDbPlace);
		
		// Converting the response to service-like response
		return PlaceConverter.convert(dbPlace);
	}	
}
