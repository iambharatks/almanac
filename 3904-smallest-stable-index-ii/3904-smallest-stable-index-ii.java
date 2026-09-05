class Solution {
    public int firstStableIndex(int[] nums, int k) {
        int [] minV = nums.clone();
        int n = nums.length;
        for(int i = n-2 ; i >= 0 ; i--){
            minV[i] = Math.min(minV[i+1],minV[i]);
        }
        int maxV = Integer.MIN_VALUE;
        for(int i = 0 ; i < n ; i++){
            maxV = Math.max(maxV,nums[i]);
            if(maxV-minV[i] <= k) return i;
        }
        return -1;
    }
}