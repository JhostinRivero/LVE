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
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;

import org.adempiere.core.domains.models.I_C_Order;
import org.compiere.model.MBPartner;
import org.compiere.model.MCurrency;
import org.compiere.model.MDocType;
import org.compiere.model.MOrder;
import org.compiere.model.MOrgInfo;
import org.compiere.model.Query;
import org.compiere.util.Env;
import org.erpya.lve.model.MLVEList;
import org.erpya.lve.model.MLVEListVersion;
import org.erpya.lve.model.MLVEWithholdingTax;
import org.spin.model.I_WH_Withholding;
import org.spin.model.MWHSetting;
import org.spin.model.MWHWithholding;
import org.spin.util.AbstractWithholdingSetting;

/**
 * 	Implementación de retención de Impuestos Municipales para la locacización de Venezuela
 * 	Note que básicamente se realiza una validación del documento en cuestión y luego se procesa
 * 	la generación del documento de retención o el log del documento queda a libertad de la clase abstracta
 * 	@author Yamel Senih, ysenih@erpcya.com, ERPCyA http://www.erpcya.com
 *  @contributor Carlos Parada, cparada@erpya.com, ERPCyA http://www.erpya.com
 */
public class OrderIM extends AbstractWithholdingSetting {

	public OrderIM(MWHSetting setting) {
		super(setting);
	}
	/**	Current Order	*/
	private MOrder order;
	/**	Current Business Partner	*/
	private MBPartner businessPartner;
	/**	Withholding Rental Exempt for Business Partner	*/
	private MLVEList activityToApply= null;
	/**Withholding Rental Rates to Apply*/
	private MLVEListVersion rateToApply= null;
	/**Currency Precision */
	int curPrecision = 0 ;
	/**Manual Withholding*/
	private boolean isManual = false;
	
	/**Base Amount*/
	private BigDecimal baseAmount = Env.ZERO;
	@Override
	public boolean isValid() {
		boolean isValid = true;
		//	Validate Document
		if(getDocument().get_Table_ID() != I_C_Order.Table_ID) {
			addLog("@C_Order_ID@ @NotFound@");
			return false;
		}
		order = (MOrder) getDocument();
		businessPartner = (MBPartner) order.getC_BPartner();
		
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
			if (order != null) {
				MCurrency currency = (MCurrency) order.getC_Currency();
				curPrecision = currency.getStdPrecision();
				baseAmount = order.getTotalLines();
			}
			//	Add reference
			setReturnValue(I_WH_Withholding.COLUMNNAME_SourceOrder_ID, order.getC_Order_ID());
			MLVEWithholdingTax currentWHTax = MLVEWithholdingTax.getFromClient(getContext(), getDocument().getAD_Org_ID(),MLVEWithholdingTax.TYPE_ImpuestoMunicipal);
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
			//	Validate Reversal
			if(!order.getDocStatus().equals(MOrder.DOCSTATUS_Completed)) {
				addLog("@Invalid@ @C_Order_ID@ @DocStatus@");
				isValid = false;
			}
			
			MDocType documentType = MDocType.get(getContext(), order.getC_DocTypeTarget_ID());
			if(documentType == null) {
				addLog("@C_DocType_ID@ @NotFound@");
				isValid = false;
			}
			//	Validate Exempt Business Partner
			if(businessPartner.get_ValueAsBoolean(LVEUtil.COLUMNNAME_IsWithholdingMunicipalExempt)) {
				isValid = false;
				addLog("@C_BPartner_ID@ @IsWithholdingMunicipalExempt@");
			}
			//	Validate Withholding Definition
			setActivity();
			if (activityToApply==null) {
				isValid = false;
				addLog("@NotFound@ @BusinessActivity_ID@");
			}
			
			setRate();
			if (rateToApply == null) {
				isValid = false;
				addLog("@NotFound@ @WithholdingMunicipalRate_ID@");
			}
			
			//Validate if Document is Generated
			if (isGenerated()) {
				isValid = false;
			}
		}
		
		return isValid;
	}

	@Override
	public String run() {
		if (activityToApply!=null
				&& rateToApply!=null) {
			BigDecimal rate = rateToApply.getAmount();
			
			if (rate==null) 
				rate = Env.ZERO;
			
			if (rate.compareTo(Env.ZERO)!=0) {
				setWithholdingRate(rate);
				rate = getWithholdingRate(true);
				addBaseAmount(baseAmount);
				addWithholdingAmount(baseAmount.multiply(rate,MathContext.DECIMAL128)
												.setScale(curPrecision, RoundingMode.HALF_UP));
				addDescription(activityToApply.getName());
				setReturnValue(MWHWithholding.COLUMNNAME_IsManual, isManual);
				
				int WHThirdParty_ID = order.get_ValueAsInt(LVEUtil.COLUMNNAME_WHThirdParty_ID);
				if (WHThirdParty_ID != 0)
					setReturnValue(LVEUtil.COLUMNNAME_WHThirdParty_ID, WHThirdParty_ID);
				
				setReturnValue(MWHWithholding.COLUMNNAME_IsSimulation, true);
				saveResult();
			}
		}
		
		activityToApply = null;
		rateToApply = null;
		curPrecision = 0;
		baseAmount = null;
		return null;
	}
	
	/**
	 * Set concepts from order document
	 */
	private void setActivity() {
		if (businessPartner!=null) {
			if (businessPartner.get_ValueAsInt(LVEUtil.COLUMNNAME_BusinessActivity_ID)!=0)
				activityToApply = new MLVEList(getContext(), businessPartner.get_ValueAsInt(LVEUtil.COLUMNNAME_BusinessActivity_ID), businessPartner.get_TrxName());
		}
	}
	
	/**
	 * Set Rates
	 */
	private void setRate() {
		if (businessPartner!=null) {
			if (businessPartner.get_ValueAsInt(LVEUtil.COLUMNNAME_WithholdingMunicipalRate_ID)!=0)
				rateToApply = new MLVEListVersion(getContext(), businessPartner.get_ValueAsInt(LVEUtil.COLUMNNAME_WithholdingMunicipalRate_ID), businessPartner.get_TrxName());
		}
		
	}
	
	/**
	 * Validate if the document has withholding allocated
	 * @return
	 */
	private boolean isGenerated() {
		if (order!=null) 
			return new Query(getContext(), MWHWithholding.Table_Name, "SourceOrder_ID = ? "
																	+ "AND WH_Definition_ID = ? "
																	+ "AND WH_Setting_ID = ? "
																	+ "AND Processed = 'Y' "
																	+ "AND IsSimulation='Y' "
																	+ "AND DocStatus IN (?,?)" , getTransactionName())
						.setParameters(order.get_ID(),getDefinition().get_ID(),getSetting().get_ID(),MWHWithholding.DOCSTATUS_Completed,MWHWithholding.DOCSTATUS_Closed)
						.match();
		return false;
	}
}
