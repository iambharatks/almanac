class Solution {
public:
    int findUnsortedSubarray(vector<int>& nums) {
        int minVal = INT_MAX;
        int maxVal = INT_MIN;
        int n = size(nums);
        bool found = false;
        for(int i = 1 ; i < n ; i++){
            if(found){
                minVal = min(minVal,nums[i]);
                continue;
            }
            if(nums[i-1] > nums[i]){
                minVal = nums[i];
                found = true;
            }
        }
        found = false;
        for(int i = n-2; i>= 0 ; i--){
            if(found){
                maxVal = max(maxVal,nums[i]);
                continue;
            }
            if(nums[i] > nums[i+1]){
                maxVal = nums[i];
                found = true;
            }
        }
        int l = 0, r=n-1;
        cout<<minVal<<" "<<maxVal<<'\n';
        while(l<=r && nums[l++] <= minVal);
        l--;
        while(l <= r &&  nums[r--] >= maxVal);
        r++;
        cout<<l<<" "<<r<<"\n";
        return (l == r)?0: r-l+1;
    }
};