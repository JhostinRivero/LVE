/*************************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                              *
 * Copyright (C) 2012-2018 E.R.P. Consultores y Asociados, C.A.                      *
 * Contributor(s): Yamel Senih ysenih@erpya.com                                      *
 * This program is free software: you can redistribute it and/or modify              *
 * it under the terms of the GNU General Public License as published by              *
 * the Free Software Foundation, either version 3 of the License, or                 *
 * (at your option) any later version.                                               *
 * This program is distributed in the hope that it will be useful,                   *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of                    *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the                     *
 * GNU General Public License for more details.                                      *
 * You should have received a copy of the GNU General Public License                 *
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.            *
 ************************************************************************************/
package org.erpya.lve.util;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.adempiere.core.domains.models.I_C_Order;
import org.adempiere.core.domains.models.I_C_OrderLine;
import org.compiere.model.MBPartner;
import org.compiere.model.MDocType;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MOrderTax;
import org.compiere.model.MOrgInfo;
import org.compiere.model.MPayment;
import org.compiere.model.MTable;
import org.compiere.model.MTax;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.compiere.util.Util;
import org.erpya.lve.model.MLVEList;
import org.erpya.lve.model.MLVEWithholdingTax;
import org.spin.model.I_WH_Withholding;
import org.spin.store.model.MCPaymentMethod;
import org.spin.model.MWHSetting;
import org.spin.model.MWHWithholding;
import org.spin.util.AbstractWithholdingSetting;

/**
 * 	Implementación de retención de I.V.A para la localización de Venezuela
 * 	Esto puede aplicar para Documentos por Pagar y Notas de Crédito de Documentos por Pagar
 * 	Note que la validación de las 20 UT solo aplica para documentos por pagar
 * 	@author Yamel Senih, ysenih@erpcya.com, ERPCyA http://www.erpcya.com
 *  @contributor Carlos Parada, cparada@erpya.com, ERPCyA http://www.erpya.com
 */
public class POSOrderIVABase extends AbstractWithholdingSetting {

	public POSOrderIVABase(MWHSetting setting) {
		super(setting);
	}
	/**	Current order	*/
	private MOrder order;
	/**	Current Business Partner	*/
	private MBPartner businessPartner;
	/**	Taxes	*/
	private List<MOrderTax> taxes;
	/**Manual Withholding*/
	private boolean isManual = false;
	/**Withholding Rate*/
	BigDecimal withholdingRate = Env.ZERO;
	/**	Default POS Payment Type	*/
	private PO defaultPaymentMethodallocated;
	
	public PO getDefaultPaymentMethodAllocated() {
		if(defaultPaymentMethodallocated != null) {
			return defaultPaymentMethodallocated;
		}
		defaultPaymentMethodallocated = new Query(getContext(), "C_POSPaymentTypeAllocation", 
				"C_POS_ID = ? "
				+ "AND IsPaymentReference = 'Y' "
				+ "AND EXISTS(SELECT 1 FROM C_PaymentMethod pm "
				+ "WHERE pm.C_PaymentMethod_ID = C_POSPaymentTypeAllocation.C_PaymentMethod_ID "
				+ "AND pm.TenderType = ? "
				+ "AND pm.WH_Type_ID = ?)", getTransactionName())
				.setParameters(order.getC_POS_ID(), MPayment.TENDERTYPE_CreditMemo, getSetting().getWH_Type_ID())
				.setOnlyActiveRecords(true)
				.first();
		return defaultPaymentMethodallocated;
	}

	public void setDefaultPaymentMethodAllocated(PO defaultPaymentMethodAllocated) {
		this.defaultPaymentMethodallocated = defaultPaymentMethodAllocated;
	}
	
	@Override
	public boolean isValid() {
		boolean isValid = true;
		//	Validate Document
		if(getDocument().get_Table_ID() != I_C_OrderLine.Table_ID
				&& getDocument().get_Table_ID() != I_C_Order.Table_ID) {
			return false;
		}
		String event = getSetting().getEventModelValidator();
		if(Util.isEmpty(event)) {
			return false;
		}
		if(getDocument().get_Table_ID() == I_C_OrderLine.Table_ID) {
			if(!(getDocument().is_ValueChanged("LineNetAmt")
					|| getDocument().is_ValueChanged("C_Tax_ID"))
					&& !event.equals(MWHSetting.EVENTMODELVALIDATOR_TableAfterNew)) {
				return false;
			}
			MOrderLine orderLine = (MOrderLine) getDocument();
			order = orderLine.getParent();
		} else {
			if(!(getDocument().is_ValueChanged("C_DocTypeTarget_ID")
					|| getDocument().is_ValueChanged("C_BPartner_ID"))) {
				return false;
			}
			order = (MOrder) getDocument();
		}
		if(order.getC_POS_ID() <= 0) {
			return false;
		}
		businessPartner = (MBPartner) order.getC_BPartner();
		//	Validate Processed
		if(order.isProcessed()) {
			return false;
		}
		if(!businessPartner.get_ValueAsBoolean(LVEUtil.COLUMNNAME_IsTaxpayer)) {
			deleteReference();
			return false;
		}
		//Valid Business Partner
		Optional.ofNullable(order)
				.ifPresent(order ->{
					//Add reference
					setReturnValue(I_WH_Withholding.COLUMNNAME_SourceOrder_ID, order.getC_Order_ID());
					setReturnValue(I_WH_Withholding.COLUMNNAME_AD_Org_ID, order.getAD_Org_ID());
					if (order.isSOTrx()) {
						isManual = true;
						Optional.ofNullable(MOrgInfo.get(getContext(), order.getAD_Org_ID(), order.get_TrxName()))
								.ifPresent(orgInfo ->{
								businessPartner = MBPartner.get(getContext(), orgInfo.get_ValueAsInt(LVEUtil.COLUMNNAME_WH_BPartner_ID));
						});
					}else
						isManual = false;
				});
		
		if (businessPartner==null) {
			addLog("@C_BPartner_ID@ @NotFound@");
			isValid = false;
		} else {
			//	Add reference
			setReturnValue(I_WH_Withholding.COLUMNNAME_SourceOrder_ID, order.getC_Order_ID());
			MLVEWithholdingTax currentWHTax = MLVEWithholdingTax.getFromClient(getContext(), getDocument().getAD_Org_ID(),MLVEWithholdingTax.TYPE_IVA);
			//	Validate if exists Withholding Tax Definition for client
			if(currentWHTax == null) {
				addLog("@LVE_WithholdingTax_ID@ @NotFound@");
				isValid = false;
			}
			
			//	Validate if withholding if exclude for client
			if(currentWHTax!=null 
					&& currentWHTax.isClientExcluded()) {
				addLog("@IsClientExcluded@ " + currentWHTax.getName());
				isValid = false;
			}
			
			MDocType documentType = MDocType.get(getContext(), order.getC_DocTypeTarget_ID());
			if(documentType == null) {
				addLog("@C_DocType_ID@ @NotFound@");
				isValid = false;
			}
			//	Validate Exempt Document
			if(order.get_ValueAsBoolean(LVEUtil.COLUMNNAME_IsWithholdingTaxExempt)) {
				deleteReference();
				return false;
			}
			//	Validate Exempt Business Partner
			if(businessPartner.get_ValueAsBoolean(LVEUtil.COLUMNNAME_IsWithholdingTaxExempt)) {
				deleteReference();
				return false;
			}
			//	Validate Withholding Definition
			//MLVEWithholdingTax withholdingTaxDefinition = MLVEWithholdingTax.getFromClient(getContext(), order.getAD_Org_ID());
			int withholdingRateId = businessPartner.get_ValueAsInt(LVEUtil.COLUMNNAME_WithholdingTaxRate_ID);
			if(withholdingRateId == 0
					&& currentWHTax!=null) {
				withholdingRateId = currentWHTax.getDefaultWithholdingRate_ID();
			}
			//	Validate Definition
			if(withholdingRateId == 0) {
				deleteReference();
				return false;
			} else {
				withholdingRate = MLVEList.get(getContext(), withholdingRateId).getListVersionAmount(order.getDateOrdered());
				setWithholdingRate(withholdingRate);
			}
			//	Validate Tax
			if(getWithholdingRate().equals(Env.ZERO)) {
				deleteReference();
				return false;
			}
			//	Validate Tribute Unit
			
			BigDecimal tributeUnitAmount = Env.ZERO;
			if (currentWHTax != null)
				tributeUnitAmount = currentWHTax.getValidTributeUnitAmount(order.getDateAcct());
			
			if(tributeUnitAmount.equals(Env.ZERO)) {
				addLog("@TributeUnit@ (@Rate@ @NotFound@)");
				isValid = false;
			}
			//	Validate if it have taxes
			taxes = Arrays.asList(order.getTaxes(true))
				.stream()
				.filter(orderTax -> MTax.get(getContext(), orderTax.getC_Tax_ID()).get_ValueAsBoolean(LVEUtil.COLUMNNAME_IsWithholdingTaxApplied) 
						&& orderTax.getTaxAmt() != null 
						&& orderTax.getTaxAmt().compareTo(Env.ZERO) > 0)
				.collect(Collectors.toList());
		}
		if(getDefaultPaymentMethodAllocated() == null) {
			addLog("@C_PaymentMethod_ID@ @NotFound@");
			isValid = false;
		}
		return isValid;
		
	}

	@Override
	public String run() {
		MTable posPaymentType = MTable.get(getContext(), "C_POSPaymentTypeAllocation");
		if(posPaymentType != null) {
			PO paymentTypeAllocation = getDefaultPaymentMethodAllocated();
			if(paymentTypeAllocation == null) {
				return null;
			}
			if(Util.isEmpty(paymentTypeAllocation.get_ValueAsString("Name"))) {
				MCPaymentMethod paymentMethod = MCPaymentMethod.getById(getContext(), paymentTypeAllocation.get_ValueAsInt("C_PaymentMethod_ID"), getTransactionName());
				if(!Util.isEmpty(paymentMethod.getDescription())) {
					addDescription(paymentMethod.getDescription() + " @of@ " + order.getDocumentNo());
				}
			} else {
				addDescription(paymentTypeAllocation.get_ValueAsString("Name") + " @of@ " + order.getDocumentNo());
			}
		}
		//	Iterate
		taxes.forEach(orderTax -> {
			setWithholdingRate(withholdingRate);
			addBaseAmount(orderTax.getTaxAmt());
			addWithholdingAmount(orderTax.getTaxAmt().multiply(getWithholdingRate(true)));
			MTax tax = MTax.get(getContext(), orderTax.getC_Tax_ID());
			addDescription(tax.getName());
			setReturnValue(MWHWithholding.COLUMNNAME_IsManual, isManual);
			int WHThirdParty_ID = order.get_ValueAsInt(LVEUtil.COLUMNNAME_WHThirdParty_ID);
			if (WHThirdParty_ID != 0)
				setReturnValue(LVEUtil.COLUMNNAME_WHThirdParty_ID, WHThirdParty_ID);
			setReturnValue(MWHWithholding.COLUMNNAME_C_Tax_ID, orderTax.getC_Tax_ID());
			setReturnValue(MWHWithholding.COLUMNNAME_IsSimulation, true);
		});
		return null;
	}
	
	/**
	 * Save payment reference from withholding calculation
	 */
	protected void savePaymentReference(boolean createIfNotExists) {
		if(Optional.ofNullable(getWithholdingAmount()).orElse(Env.ZERO).compareTo(Env.ZERO) > 0) {
			//	Add backward compatibility
			MTable paymentReferenceDefinition = MTable.get(getContext(), "C_POSPaymentReference");
			if(paymentReferenceDefinition != null) {
				if(getDefaultPaymentMethodAllocated() == null) {
					return;
				}
				PO paymentReferenceToCreate = new Query(getContext(), "C_POSPaymentReference", 
						"C_Order_ID = ? "
						+ "AND TenderType = ? "
						+ "AND C_PaymentMethod_ID = ? "
						+ "AND Processed = 'N'", getTransactionName())
						.setClient_ID()
						.setParameters(order.getC_Order_ID(), MPayment.TENDERTYPE_CreditMemo, getDefaultPaymentMethodAllocated().get_ValueAsInt("C_PaymentMethod_ID")).first();
				//	Create
				if(createIfNotExists
						&& (paymentReferenceToCreate == null
							|| paymentReferenceToCreate.get_ID() <= 0)) {
					paymentReferenceToCreate = paymentReferenceDefinition.getPO(0, getTransactionName());
				}
				if(paymentReferenceToCreate != null) {
					paymentReferenceToCreate.set_ValueOfColumn("Amount", getWithholdingAmount());
					paymentReferenceToCreate.set_ValueOfColumn("AmtSource", getWithholdingAmount());
					paymentReferenceToCreate.set_ValueOfColumn("Base", getBaseAmount());
					paymentReferenceToCreate.set_ValueOfColumn("Rate", getWithholdingRate());
					paymentReferenceToCreate.set_ValueOfColumn("C_BPartner_ID", order.getC_BPartner_ID());
					paymentReferenceToCreate.set_ValueOfColumn("C_ConversionType_ID", order.getC_ConversionType_ID());
					paymentReferenceToCreate.set_ValueOfColumn("C_Currency_ID", order.getC_Currency_ID());
					paymentReferenceToCreate.set_ValueOfColumn("C_Order_ID", order.getC_Order_ID());
					paymentReferenceToCreate.set_ValueOfColumn("C_POS_ID", order.getC_POS_ID());
					if(order.getSalesRep_ID() > 0) {
						paymentReferenceToCreate.set_ValueOfColumn("SalesRep_ID", order.getSalesRep_ID());
					}
					paymentReferenceToCreate.set_ValueOfColumn("IsReceipt", true);
					paymentReferenceToCreate.set_ValueOfColumn("IsKeepReferenceAfterProcess", false);
					paymentReferenceToCreate.set_ValueOfColumn("TenderType", MPayment.TENDERTYPE_CreditMemo);
					paymentReferenceToCreate.set_ValueOfColumn("IsAutoCreatedReference", true);
					paymentReferenceToCreate.set_ValueOfColumn("C_PaymentMethod_ID", getDefaultPaymentMethodAllocated().get_ValueAsInt("C_PaymentMethod_ID"));
					paymentReferenceToCreate.set_ValueOfColumn("TenderType", MPayment.TENDERTYPE_CreditMemo);
					paymentReferenceToCreate.set_ValueOfColumn("Description", Msg.parseTranslation(getContext(), getProcessDescription()));
					paymentReferenceToCreate.set_ValueOfColumn("PayDate", order.getDateOrdered());
					paymentReferenceToCreate.setAD_Org_ID(order.getAD_Org_ID());
					paymentReferenceToCreate.saveEx();
				}
			}
			//	Clear
			setWithholdingRate(Env.ZERO);
			setBaseAmount(Env.ZERO);
			setWithholdingAmount(Env.ZERO);
			setDefaultPaymentMethodAllocated(null);
		} else {
			deleteReference();
		}
	}
	
	/**
	 * Delete payment reference
	 */
	private void deleteReference() {
		MTable paymentReferenceDefinition = MTable.get(getContext(), "C_POSPaymentReference");
		if(paymentReferenceDefinition != null) {
			if(getDefaultPaymentMethodAllocated() == null) {
				return;
			}
			PO paymentReferenceToDelete = new Query(getContext(), "C_POSPaymentReference", 
					"C_Order_ID = ? "
					+ "AND TenderType = ? "
					+ "AND C_PaymentMethod_ID = ?", getTransactionName())
					.setClient_ID()
					.setParameters(order.getC_Order_ID(), MPayment.TENDERTYPE_CreditMemo, getDefaultPaymentMethodAllocated().get_ValueAsInt("C_PaymentMethod_ID")).first();
			if(paymentReferenceToDelete != null) {
				paymentReferenceToDelete.deleteEx(true);
			}
		}
	}
}


