/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.billing.web.controller.main;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.billing.includable.billcalculator.BillCalculatorService;
import org.openmrs.module.hospitalcore.BillingService;
import org.openmrs.module.hospitalcore.model.BillableService;
import org.openmrs.module.hospitalcore.model.PatientServiceBill;
import org.openmrs.module.hospitalcore.model.PatientServiceBillItem;
import org.openmrs.module.hospitalcore.util.Money;
import org.openmrs.module.hospitalcore.util.PatientUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 *
 */
@Controller
@RequestMapping("/module/billing/editPatientServiceBill.form")
public class BillableServiceBillEditController {
	
	private Log logger = LogFactory.getLog(getClass());

	@RequestMapping(method = RequestMethod.GET)
	public String viewForm(Model model, @RequestParam("billId") Integer billId,
			@RequestParam("patientId") Integer patientId) {

		BillingService billingService = Context
				.getService(BillingService.class);
		List<BillableService> services = billingService.getAllServices();
		Map<Integer, BillableService> mapServices = new HashMap<Integer, BillableService>();
		for (BillableService ser : services) {
			mapServices.put(ser.getConceptId(), ser);
		}
		Integer conceptId = Integer.valueOf(Context.getAdministrationService()
				.getGlobalProperty("billing.rootServiceConceptId"));
		Concept concept = Context.getConceptService().getConcept(conceptId);
		model.addAttribute("tabs",
				billingService.traversTab(concept, mapServices, 1));
		model.addAttribute("patientId", patientId);
		PatientServiceBill bill = billingService
				.getPatientServiceBillById(billId);

		model.addAttribute("bill", bill);
		return "/module/billing/main/billableServiceBillEdit";
	}

	@RequestMapping(method = RequestMethod.POST)
	public String onSubmit(Model model, Object command,
			BindingResult bindingResult, HttpServletRequest request,
			@RequestParam("cons") Integer[] cons,
			@RequestParam("patientId") Integer patientId,
			@RequestParam("billId") Integer billId,
			@RequestParam("action") String action) {

		validate(cons, bindingResult, request);
		if (bindingResult.hasErrors()) {
			model.addAttribute("errors", bindingResult.getAllErrors());
			return "module/billing/main/patientServiceBillEdit";
		}
		BillingService billingService = Context
				.getService(BillingService.class);

		PatientServiceBill bill = billingService
				.getPatientServiceBillById(billId);
		
		// Get the BillCalculator to calculate the rate of bill item the patient has to pay
		Patient patient = Context.getPatientService().getPatient(patientId);
		Map<String, String> attributes = PatientUtil.getAttributes(patient);
		BillCalculatorService calculator = new BillCalculatorService();

		if ("void".equalsIgnoreCase(action)) {
			bill.setVoided(true);
			bill.setVoidedDate(new Date());
			for (PatientServiceBillItem item : bill.getBillItems()) {
				item.setVoided(true);
				item.setVoidedDate(new Date());
			}
			billingService.savePatientServiceBill(bill);
			return "redirect:/module/billing/patientServiceBill.list?patientId="
					+ patientId;
		}

		// void old items and reset amount
		Map<Integer, PatientServiceBillItem> mapOldItems = new HashMap<Integer, PatientServiceBillItem>();
		for (PatientServiceBillItem item : bill.getBillItems()) {
			item.setVoided(true);
			item.setVoidedDate(new Date());
			mapOldItems.put(item.getPatientServiceBillItemId(), item);
		}
		bill.setAmount(BigDecimal.ZERO);
		bill.setPrinted(false);

		PatientServiceBillItem item;
		int quantity = 0;
		Money itemAmount;
		Money mUnitPrice;
		Money totalAmount = new Money(BigDecimal.ZERO);
		BigDecimal totalActualAmount = new BigDecimal(0);
		BigDecimal unitPrice;
		String name;
		BillableService service;

		for (int conceptId : cons) {

			unitPrice = NumberUtils.createBigDecimal(request
					.getParameter(conceptId + "_unitPrice"));
			quantity = NumberUtils.createInteger(request.getParameter(conceptId
					+ "_qty"));
			name = request.getParameter(conceptId + "_name");
			service = billingService.getServiceByConceptId(conceptId);

			mUnitPrice = new Money(unitPrice);
			itemAmount = mUnitPrice.times(quantity);
			totalAmount = totalAmount.plus(itemAmount);			

			String sItemId = request.getParameter(conceptId + "_itemId");
			
			if (sItemId == null) {
				item = new PatientServiceBillItem();
				
				// Get the ratio for each bill item
				BigDecimal rate = calculator.getRate(patient, attributes, item);				
				
				item.setAmount(itemAmount.getAmount());
				item.setActualAmount(item.getAmount().multiply(rate));
				totalActualAmount = totalActualAmount.add(item.getActualAmount());
				item.setCreatedDate(new Date());
				item.setName(name);
				item.setPatientServiceBill(bill);
				item.setQuantity(quantity);
				item.setService(service);
				item.setUnitPrice(unitPrice);
				bill.addBillItem(item);
			} else {
				
				item = mapOldItems.get(Integer.parseInt(sItemId));
				
				// Get the ratio for each bill item
				BigDecimal rate = calculator.getRate(patient, attributes, item);				
				
				item.setVoided(false);
				item.setVoidedDate(null);
				item.setQuantity(quantity);
				item.setAmount(itemAmount.getAmount());
				item.setActualAmount(item.getAmount().multiply(rate));
				totalActualAmount = totalActualAmount.add(item.getActualAmount());
			}
		}
		bill.setAmount(totalAmount.getAmount());
		bill.setActualAmount(totalActualAmount);
		
		// Determine whether the bill is free or not
		bill.setFreeBill(calculator.isFreeBill(patient, attributes));
		logger.info("Is free bill: " + bill.getFreeBill());
		
		bill = billingService.savePatientServiceBill(bill);
		return "redirect:/module/billing/patientServiceBill.list?patientId="
				+ patientId + "&billId=" + billId;
	}

	private void validate(Integer[] ids, BindingResult binding,
			HttpServletRequest request) {
		for (int id : ids) {
			try {
				Integer.parseInt(request.getParameter(id + "_qty"));
			} catch (Exception e) {
				binding.reject("billing.bill.quantity.invalid",
						"Quantity is invalid");
				return;
			}
		}
	}
}
